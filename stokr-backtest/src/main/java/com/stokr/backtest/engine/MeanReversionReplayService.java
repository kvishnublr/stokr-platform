package com.stokr.backtest.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stokr.backtest.domain.BacktestRun;
import com.stokr.backtest.domain.BacktestStatus;
import com.stokr.backtest.execution.BacktestEvaluationContext;
import com.stokr.backtest.execution.BacktestExecutionStages;
import com.stokr.backtest.execution.BacktestRiskGate;
import com.stokr.backtest.execution.CandleStreamQualityValidator;
import com.stokr.backtest.execution.ExecutionContext;
import com.stokr.backtest.repository.BacktestRunRepository;
import com.stokr.backtest.service.BacktestReplayOutcome;
import com.stokr.backtest.service.BacktestResultService;
import com.stokr.backtest.service.ReplayLoopTelemetry;
import com.stokr.backtest.strategy.BacktestStrategyRegistry;
import com.stokr.backtest.web.dto.ExecutionRequestDto;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.BadRequestException;
import com.stokr.execution.pipeline.SignalExecutionBridge;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.oms.journal.EventJournalService;
import com.stokr.oms.journal.ReplayCheckpointService;
import com.stokr.oms.journal.StreamKeys;
import com.stokr.oms.journal.domain.EventStoreEntry;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.meanreversion.runtime.MeanReversionReplayState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Candle replay orchestrator: routes to registered {@link com.stokr.backtest.strategy.BacktestStrategyPlugin}s.
 * <p>PR-3: chunked candle loading, immutable {@link ExecutionContext}, mean-reversion runtime parameters
 * wired through plugins, staged pipeline logging, and deterministic correlation IDs per bar.</p>
 * <p>PR-4: execution lifecycle states, append-only {@code oms_execution_events}, resume-safe mean-reversion
 * strategy state in recovery metadata, and candle stream quality validation.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeanReversionReplayService {

    private static final int META_VERSION = 1;
    private static final int CANDLE_PAGE_SIZE = 2500;
    /**
     * For bars with no signal, skip journal + resume checkpoint most iterations — otherwise long 1m replays
     * issue O(bars) DB writes and appear "stuck" in RUNNING for tens of minutes. Signals always checkpoint.
     */
    private static final int NO_SIGNAL_CHECKPOINT_EVERY = 250;

    private final SignalExecutionBridge signalExecutionBridge;
    private final BacktestRunRepository runRepository;
    private final MarketDataQueryService marketDataQueryService;
    private final BacktestResultService backtestResultService;
    private final EventJournalService eventJournalService;
    private final ReplayCheckpointService replayCheckpointService;
    private final ObjectMapper objectMapper;
    private final BacktestStrategyRegistry strategyRegistry;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    public BacktestReplayOutcome runReplay(
            ExecutionRequestDto request,
            UUID userId,
            int metadataSchemaVersion,
            long strategyDefinitionVersion
    ) {
        return runReplay(request, userId, metadataSchemaVersion, strategyDefinitionVersion, null);
    }

    public BacktestReplayOutcome runReplay(
            ExecutionRequestDto request,
            UUID userId,
            int metadataSchemaVersion,
            long strategyDefinitionVersion,
            ReplayProgressCallback progress
    ) {
        String sk = normalizeStrategyKey(request.strategyKey());
        String tf = request.timeframe().trim();
        String stepTf = effectiveStepTimeframe(sk, tf);
        Instant start = request.range().from();
        Instant end = request.range().to();
        long seed = request.seed() != null ? request.seed() : 0L;
        String symbol = request.symbol().trim();
        String executionProfile = request.executionProfile();
        String correlationId = Optional.ofNullable(CorrelationIdHolder.get())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());

        long totalBars = marketDataQueryService.rangeCount(symbol, stepTf, start, end);
        log.info(
                "backtest.replay.preflight symbol={} stepTf={} displayTf={} rangeStart={} rangeEnd={} candleCount={} correlationId={}",
                symbol,
                stepTf,
                tf,
                start,
                end,
                totalBars,
                correlationId
        );
        if (totalBars <= 0) {
            log.warn(
                    "backtest.replay.no_candles symbol={} stepTf={} rangeStart={} rangeEnd={} correlationId={}",
                    symbol,
                    stepTf,
                    start,
                    end,
                    correlationId
            );
            throw new BadRequestException(
                    "No market data for replay: symbol \""
                            + symbol
                            + "\" has no "
                            + stepTf
                            + " candles between the requested timestamps. "
                            + "For local development set STOKR_REPLAY_SEED_SYNTHETIC=true (Docker default) or ingest ticks via POST /api/marketdata/ticks."
            );
        }

        BacktestRun run = new BacktestRun();
        run.setStrategyKey(sk);
        run.setSymbol(symbol);
        run.setStatus(BacktestStatus.RUNNING);
        run.setSeed(seed);
        run.setUserId(userId != null ? userId : systemUserId);
        run.setTimeframe(tf);
        run.setRangeStart(start);
        run.setRangeEnd(end);
        run.setExecutionProfile(executionProfile);
        run.setExecutionRequestJson(canonicalExecutionJson(request));
        run.setMetadataSchemaVersion(metadataSchemaVersion);
        run.setStrategyDefinitionVersion(strategyDefinitionVersion);
        run.setExecutionMode(request.executionMode().toUpperCase());
        run.setFeeModel(request.feeModel());
        run.setSlippageModel(request.slippageModel());
        run = runRepository.save(run);
        UUID runId = run.getId();
        UUID uid = run.getUserId();

        if (progress != null) {
            progress.onRunPersisted(runId);
        }

        log.info(
                "backtest.execution.run runId={} strategyKey={} metadataSchemaVersion={} strategyDefinitionVersion={} seed={} correlationId={}",
                runId,
                sk,
                metadataSchemaVersion,
                strategyDefinitionVersion,
                seed,
                correlationId
        );

        try {
            EventStoreEntry started = appendJournal(runId, uid, symbol, sk, "BACKTEST_RUN_STARTED", Map.of(
                    "seed", seed,
                    "start", start.toString(),
                    "end", end.toString(),
                    "barCount", totalBars,
                    "strategyKey", sk,
                    "timeframe", tf,
                    "stepTimeframe", stepTf,
                    "metadataSchemaVersion", metadataSchemaVersion,
                    "strategyDefinitionVersion", strategyDefinitionVersion,
                    "correlationId", correlationId
            ));
            int totalBarsInt = totalBars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalBars;
            if (progress != null) {
                progress.onTotalsKnown(runId, totalBarsInt);
            }
            MeanReversionReplayState mrState = StrategyKeys.MEAN_REVERSION_RANGE_FADE.equals(sk) ? new MeanReversionReplayState() : null;
            upsertCp(runId, uid, started, metaJson(sk, symbol, start, end, seed, uid, tf, stepTf, 0, totalBarsInt, mrState));
            BacktestEvaluationContext evalCtx = buildEvaluationContext(run, correlationId, mrState);

            BacktestReplayOutcome outcome = executeLoop(
                    run, evalCtx, uid, start, end, seed, sk, tf, stepTf, 0, totalBarsInt, correlationId, progress);
            log.info(
                    "backtest.replay.outcome runId={} materialized={} totalTrades={} tradeRows={} equityPoints={} totalPnl={}",
                    runId,
                    outcome.materialized(),
                    outcome.metrics() != null ? outcome.metrics().totalTrades() : -1,
                    outcome.trades() != null ? outcome.trades().size() : -1,
                    outcome.equityCurve() != null ? outcome.equityCurve().size() : -1,
                    outcome.metrics() != null ? outcome.metrics().totalPnl() : "n/a"
            );
            run.setStatus(BacktestStatus.COMPLETED);
            runRepository.save(run);
            EventStoreEntry done = appendJournal(runId, uid, symbol, sk, "BACKTEST_RUN_COMPLETED", Map.of("status", "COMPLETED"));
            upsertCp(runId, uid, done, metaJson(sk, symbol, start, end, seed, uid, tf, stepTf, totalBarsInt, totalBarsInt, mrState));
            log.info("backtest.replay.done runId={}", runId);
            return outcome;
        } catch (ReplayCancelledException cx) {
            appendJournal(runId, uid, symbol, sk, "BACKTEST_RUN_CANCELLED", Map.of(
                    "message", "User cancelled async replay",
                    "correlationId", correlationId
            ));
            backtestResultService.persistForRun(run, null);
            log.warn("backtest.replay.cancelled runId={}", runId);
            throw cx;
        } catch (RuntimeException ex) {
            log.error("backtest.replay.failed runId={}", runId, ex);
            run.setStatus(BacktestStatus.FAILED);
            runRepository.save(run);
            appendJournal(runId, uid, symbol, sk, "BACKTEST_RUN_FAILED", Map.of(
                    "error", ex.getClass().getSimpleName(),
                    "message", ex.getMessage() != null ? ex.getMessage() : ""
            ));
            throw ex;
        }
    }

    private String canonicalExecutionJson(ExecutionRequestDto request) {
        try {
            return objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("execution request serialization failed", e);
        }
    }

    public BacktestReplayOutcome resumeReplay(UUID runId) {
        BacktestRun run = runRepository.findById(runId).orElseThrow(() -> new BadRequestException("Backtest run not found"));
        if (run.getStatus() == BacktestStatus.COMPLETED) {
            throw new BadRequestException("Run already completed");
        }
        if (run.getStatus() != BacktestStatus.RUNNING && run.getStatus() != BacktestStatus.FAILED) {
            throw new BadRequestException("Run must be RUNNING or FAILED to resume");
        }
        String streamKey = StreamKeys.backtest(runId);
        ReplayCheckpointService.ReplayResumeValidation v =
                replayCheckpointService.validateForResume(StreamKeys.ST_BACKTEST, streamKey);
        if (!v.canResume()) {
            throw new IllegalStateException("Replay resume validation failed: " + v.detail());
        }
        RecoveryMeta meta = parseRecovery(v.checkpoint().orElseThrow().getRecoveryMetadata());
        UUID uid = meta.userId();
        String stepTf = effectiveStepTimeframe(meta.strategyKey(), meta.timeframe());
        String symbol = meta.symbol() != null && !meta.symbol().isBlank() ? meta.symbol() : run.getSymbol();
        long totalBars = marketDataQueryService.rangeCount(symbol, stepTf, meta.start(), meta.end());
        if (totalBars != meta.totalBars()) {
            log.warn("backtest.resume.bar_count_mismatch runId={} expected={} actual={}", runId, meta.totalBars(), totalBars);
        }
        int startIndex = Math.min(meta.nextCandleIndex(), totalBars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalBars);
        run.setStatus(BacktestStatus.RUNNING);
        runRepository.save(run);
        String correlationId = Optional.ofNullable(CorrelationIdHolder.get())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        try {
            int totalBarsInt = totalBars > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalBars;
            MeanReversionReplayState mrState = StrategyKeys.MEAN_REVERSION_RANGE_FADE.equals(meta.strategyKey())
                    ? new MeanReversionReplayState()
                    : null;
            if (mrState != null && meta.meanReversionStateSnapshot() != null && !meta.meanReversionStateSnapshot().isBlank()) {
                mrState.applySnapshotOrReset(meta.meanReversionStateSnapshot());
            }
            BacktestEvaluationContext evalCtx = buildEvaluationContext(run, correlationId, mrState);
            BacktestReplayOutcome outcome = executeLoop(run, evalCtx, uid, meta.start(), meta.end(), meta.seed(),
                    meta.strategyKey(), meta.timeframe(), stepTf, startIndex, totalBarsInt, correlationId, null);
            run.setStatus(BacktestStatus.COMPLETED);
            runRepository.save(run);
            EventStoreEntry done = appendJournal(runId, uid, symbol, meta.strategyKey(), "BACKTEST_RUN_COMPLETED",
                    Map.of("status", "COMPLETED", "resumed", Boolean.TRUE));
            upsertCp(runId, uid, done, metaJson(meta.strategyKey(), symbol, meta.start(), meta.end(), meta.seed(), uid,
                    meta.timeframe(), stepTf, totalBarsInt, totalBarsInt, mrState));
            log.info("backtest.replay.resume.done runId={} completedJournalSeq={}", runId, done.getSequenceNum());
            return outcome;
        } catch (RuntimeException ex) {
            log.error("backtest.resume.failed runId={}", runId, ex);
            run.setStatus(BacktestStatus.FAILED);
            runRepository.save(run);
            appendJournal(runId, uid, symbol, meta.strategyKey(), "BACKTEST_RUN_FAILED", Map.of(
                    "error", ex.getClass().getSimpleName(),
                    "message", ex.getMessage() != null ? ex.getMessage() : "",
                    "resumed", Boolean.TRUE
            ));
            throw ex;
        }
    }

    private ExecutionRequestDto parseExecutionRequest(BacktestRun run) {
        String json = run.getExecutionRequestJson();
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Backtest run " + run.getId() + " has no execution_request_json");
        }
        try {
            return objectMapper.readValue(json, ExecutionRequestDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid execution_request_json", e);
        }
    }

    private BacktestEvaluationContext buildEvaluationContext(BacktestRun run, String correlationId, MeanReversionReplayState mrState) {
        ExecutionRequestDto envelope = parseExecutionRequest(run);
        int meta = run.getMetadataSchemaVersion() != null ? run.getMetadataSchemaVersion() : 0;
        long sv = run.getStrategyDefinitionVersion() != null ? run.getStrategyDefinitionVersion() : 0L;
        ExecutionContext ec = ExecutionContext.from(run, envelope, meta, sv, correlationId);
        return new BacktestEvaluationContext(ec, mrState, "BACKTEST");
    }

    private BacktestReplayOutcome executeLoop(
            BacktestRun run,
            BacktestEvaluationContext ctx,
            UUID uid,
            Instant rangeStart,
            Instant rangeEnd,
            long seed,
            String strategyKey,
            String timeframe,
            String stepTf,
            int startIndex,
            int totalBars,
            String correlationId,
            ReplayProgressCallback progress
    ) {
        UUID runId = run.getId();
        String symbol = run.getSymbol();
        Instant loopStartedAt = Instant.now();
        var plugin = strategyRegistry.require(strategyKey);
        BacktestExecutionStages stages = new BacktestExecutionStages(runId, correlationId);
        BacktestRiskGate riskGate = new BacktestRiskGate();
        int globalIndex = 0;
        int processed = 0;
        int signalsEmitted = 0;
        int page = 0;
        while (true) {
            Page<MarketdataCandle> pg = marketDataQueryService.rangeAscPage(
                    symbol,
                    stepTf,
                    rangeStart,
                    rangeEnd,
                    PageRequest.of(page, CANDLE_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "openTime"))
            );
            if (pg.isEmpty()) {
                break;
            }
            CandleStreamQualityValidator.QualityReport q = CandleStreamQualityValidator.validatePage(pg.getContent(), null);
            for (String issue : q.issues()) {
                if (issue.startsWith("invalid_ohlc") || issue.startsWith("out_of_order")) {
                    throw new BadRequestException("Market data quality failed: " + issue);
                }
            }
            if (!q.issues().isEmpty()) {
                log.warn("backtest.data.quality_soft runId={} score={} issues={}", runId, q.score100(), q.issues());
            }
            for (MarketdataCandle bar : pg.getContent()) {
                if (globalIndex < startIndex) {
                    globalIndex++;
                    reportReplayProgress(progress, runId, globalIndex, totalBars);
                    continue;
                }
                long tOpen = System.nanoTime();
                stages.candleValidated(bar);
                StrategySignalEntity sig = plugin.evaluateAtOpen(ctx, bar, globalIndex, stepTf);
                stages.signalEvaluated(sig != null, globalIndex);
                if (sig != null) {
                    signalsEmitted++;
                    stages.riskChecked(riskGate.allowNewExposure(ctx.execution(), 0));
                    String sigCid = ctx.execution().correlationId() + ":bar:" + globalIndex;
                    StrategySignalEntity saved =
                            signalExecutionBridge.persistAndExecuteSynchronously(
                                    sig, sigCid, ctx.execution().executionMode());
                    stages.orderSimulated("sync_bridge");
                    stages.fillModeled("engine_default");
                    EventStoreEntry sigTail = appendJournal(runId, uid, symbol, strategyKey, "BACKTEST_SIGNAL_GENERATED", Map.of(
                            "barOpenTime", bar.getOpenTime().toString(),
                            "signalId", saved.getId().toString(),
                            "correlationId", correlationId,
                            "barIndex", globalIndex
                    ));
                    upsertCp(runId, uid, sigTail, metaJson(strategyKey, symbol, rangeStart, rangeEnd, seed, uid, timeframe, stepTf, globalIndex + 1, totalBars, ctx.meanReversionState()));
                } else {
                    int nextIndex = globalIndex + 1;
                    boolean tailBar = nextIndex >= totalBars;
                    boolean periodic = nextIndex % NO_SIGNAL_CHECKPOINT_EVERY == 0;
                    if (periodic || tailBar) {
                        EventStoreEntry prog = appendJournal(runId, uid, symbol, strategyKey, "BACKTEST_CANDLE_PASSED", Map.of(
                                "barOpenTime", bar.getOpenTime().toString(),
                                "index", globalIndex,
                                "correlationId", correlationId
                        ));
                        upsertCp(runId, uid, prog, metaJson(strategyKey, symbol, rangeStart, rangeEnd, seed, uid, timeframe, stepTf, nextIndex, totalBars, ctx.meanReversionState()));
                    }
                }
                globalIndex++;
                processed++;
                if (log.isTraceEnabled()) {
                    log.trace("backtest.bar.timing runId={} barIndex={} nanos={}", runId, globalIndex - 1, System.nanoTime() - tOpen);
                }
                reportReplayProgress(progress, runId, globalIndex, totalBars);
            }
            if (!pg.hasNext()) {
                break;
            }
            page++;
        }
        stages.timingSummary(processed);
        log.info(
                "backtest.replay.loop_done runId={} barsProcessed={} totalBarsExpected={} signalsEmitted={} correlationId={}",
                runId,
                processed,
                totalBars,
                signalsEmitted,
                correlationId
        );
        ReplayLoopTelemetry loopTelemetry = new ReplayLoopTelemetry(totalBars, processed, signalsEmitted, loopStartedAt);
        return backtestResultService.persistForRun(run, loopTelemetry);
    }

    private static void reportReplayProgress(ReplayProgressCallback cb, UUID runId, int barsCompleted, int totalBars) {
        if (cb == null) {
            return;
        }
        if (barsCompleted % 250 == 0 || barsCompleted >= totalBars) {
            if (cb.isCancelled()) {
                throw new ReplayCancelledException();
            }
        }
        cb.onBarProgress(runId, barsCompleted, totalBars);
    }

    private static String normalizeStrategyKey(String strategyKey) {
        if (strategyKey == null || strategyKey.isBlank()) {
            return StrategyKeys.MEAN_REVERSION_RANGE_FADE;
        }
        return strategyKey.trim();
    }

    /** Mean reversion logic expects 1m microstructure; step using 1m even if UI selects another aggregate for display elsewhere. */
    private static String effectiveStepTimeframe(String strategyKey, String requestedTf) {
        if (StrategyKeys.MEAN_REVERSION_RANGE_FADE.equals(strategyKey)) {
            return "1m";
        }
        return requestedTf != null && !requestedTf.isBlank() ? requestedTf : "1m";
    }

    private void upsertCp(UUID runId, UUID uid, EventStoreEntry tail, String recoveryJson) {
        replayCheckpointService.upsertTail(tail, runId, uid, recoveryJson);
    }

    private String metaJson(
            String strategyKey,
            String symbol,
            Instant start,
            Instant end,
            long seed,
            UUID userId,
            String timeframe,
            String stepTf,
            int nextIndex,
            int totalBars,
            MeanReversionReplayState meanReversionState
    ) {
        try {
            ObjectMapper deterministic = objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", META_VERSION);
            m.put("strategyKey", strategyKey);
            m.put("timeframe", timeframe);
            m.put("stepTimeframe", stepTf);
            m.put("symbol", symbol);
            m.put("start", start.toString());
            m.put("end", end.toString());
            m.put("seed", seed);
            m.put("userId", userId.toString());
            m.put("nextCandleIndex", nextIndex);
            m.put("totalBars", totalBars);
            if (meanReversionState != null) {
                m.put("meanReversionState", meanReversionState.toSnapshotLine());
            }
            return deterministic.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("recovery metadata serialization failed", e);
        }
    }

    private RecoveryMeta parseRecovery(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("checkpoint missing recovery metadata");
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            int ver = n.path("version").asInt(META_VERSION);
            if (ver != META_VERSION) {
                throw new IllegalStateException("unsupported recovery metadata version: " + ver);
            }
            String sk = n.hasNonNull("strategyKey")
                    ? n.get("strategyKey").asText(StrategyKeys.MEAN_REVERSION_RANGE_FADE)
                    : StrategyKeys.MEAN_REVERSION_RANGE_FADE;
            String tf = n.hasNonNull("timeframe") ? n.get("timeframe").asText("1m") : "1m";
            String stepTf = n.hasNonNull("stepTimeframe") ? n.get("stepTimeframe").asText(tf) : effectiveStepTimeframe(sk, tf);
            String sym = n.path("symbol").asText("");
            String mrSnap = n.hasNonNull("meanReversionState") ? n.get("meanReversionState").asText() : null;
            return new RecoveryMeta(
                    META_VERSION,
                    sk,
                    tf,
                    stepTf,
                    sym,
                    Instant.parse(n.get("start").asText()),
                    Instant.parse(n.get("end").asText()),
                    n.get("seed").asLong(),
                    UUID.fromString(n.get("userId").asText()),
                    n.get("nextCandleIndex").asInt(),
                    n.get("totalBars").asInt(),
                    mrSnap
            );
        } catch (Exception e) {
            throw new IllegalStateException("invalid recovery metadata", e);
        }
    }

    private record RecoveryMeta(
            int version,
            String strategyKey,
            String timeframe,
            String stepTimeframe,
            String symbol,
            Instant start,
            Instant end,
            long seed,
            UUID userId,
            int nextCandleIndex,
            int totalBars,
            String meanReversionStateSnapshot
    ) {
    }

    private EventStoreEntry appendJournal(
            UUID runId,
            UUID userId,
            String symbol,
            String strategyKey,
            String eventType,
            Map<String, ?> payload
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : payload.entrySet()) {
            body.put(e.getKey(), e.getValue());
        }
        return eventJournalService.append(
                StreamKeys.ST_BACKTEST,
                StreamKeys.backtest(runId),
                eventType,
                body,
                userId,
                symbol,
                strategyKey,
                runId
        );
    }
}
