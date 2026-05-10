package com.stokr.backtest.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stokr.backtest.domain.BacktestRun;
import com.stokr.backtest.domain.BacktestStatus;
import com.stokr.backtest.repository.BacktestRunRepository;
import com.stokr.backtest.service.BacktestReplayOutcome;
import com.stokr.backtest.service.BacktestResultService;
import com.stokr.backtest.strategy.BacktestStrategyRegistry;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Candle replay orchestrator: routes to registered {@link com.stokr.backtest.strategy.BacktestStrategyPlugin}s.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeanReversionReplayService {

    private static final int META_VERSION = 1;

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
            String symbol,
            Instant start,
            Instant end,
            UUID userId,
            long seed,
            String strategyKey,
            String timeframe,
            String executionProfile
    ) {
        String sk = normalizeStrategyKey(strategyKey);
        String tf = timeframe != null && !timeframe.isBlank() ? timeframe.trim() : "1m";
        String stepTf = effectiveStepTimeframe(sk, tf);

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
        run = runRepository.save(run);
        UUID runId = run.getId();
        UUID uid = run.getUserId();

        try {
            List<MarketdataCandle> candles = marketDataQueryService.rangeAsc(symbol, stepTf, start, end);
            log.info("backtest.replay.start runId={} strategy={} stepTf={} bars={}", runId, sk, stepTf, candles.size());

            EventStoreEntry started = appendJournal(runId, uid, symbol, sk, "BACKTEST_RUN_STARTED", Map.of(
                    "seed", seed,
                    "start", start.toString(),
                    "end", end.toString(),
                    "barCount", candles.size(),
                    "strategyKey", sk,
                    "timeframe", tf,
                    "stepTimeframe", stepTf
            ));
            upsertCp(runId, uid, started, metaJson(sk, symbol, start, end, seed, uid, tf, stepTf, 0, candles.size()));

            BacktestReplayOutcome outcome = executeLoop(run, candles, 0, uid, start, end, seed, sk, tf, stepTf);
            run.setStatus(BacktestStatus.COMPLETED);
            runRepository.save(run);
            EventStoreEntry done = appendJournal(runId, uid, symbol, sk, "BACKTEST_RUN_COMPLETED", Map.of("status", "COMPLETED"));
            upsertCp(runId, uid, done, metaJson(sk, symbol, start, end, seed, uid, tf, stepTf, candles.size(), candles.size()));
            log.info("backtest.replay.done runId={}", runId);
            return outcome;
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
        List<MarketdataCandle> candles = marketDataQueryService.rangeAsc(symbol, stepTf, meta.start(), meta.end());
        if (candles.size() != meta.totalBars()) {
            log.warn("backtest.resume.bar_count_mismatch runId={} expected={} actual={}", runId, meta.totalBars(), candles.size());
        }
        int startIndex = Math.min(meta.nextCandleIndex(), candles.size());
        run.setStatus(BacktestStatus.RUNNING);
        runRepository.save(run);
        try {
            BacktestReplayOutcome outcome = executeLoop(run, candles, startIndex, uid, meta.start(), meta.end(), meta.seed(),
                    meta.strategyKey(), meta.timeframe(), stepTf);
            run.setStatus(BacktestStatus.COMPLETED);
            runRepository.save(run);
            EventStoreEntry done = appendJournal(runId, uid, symbol, meta.strategyKey(), "BACKTEST_RUN_COMPLETED",
                    Map.of("status", "COMPLETED", "resumed", Boolean.TRUE));
            upsertCp(runId, uid, done, metaJson(meta.strategyKey(), symbol, meta.start(), meta.end(), meta.seed(), uid,
                    meta.timeframe(), stepTf, candles.size(), candles.size()));
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

    private BacktestReplayOutcome executeLoop(
            BacktestRun run,
            List<MarketdataCandle> candles,
            int startIndex,
            UUID uid,
            Instant rangeStart,
            Instant rangeEnd,
            long seed,
            String strategyKey,
            String timeframe,
            String stepTf
    ) {
        UUID runId = run.getId();
        String symbol = run.getSymbol();
        var plugin = strategyRegistry.require(strategyKey);
        for (int i = startIndex; i < candles.size(); i++) {
            MarketdataCandle bar = candles.get(i);
            StrategySignalEntity sig = plugin.evaluateAtOpen(symbol, uid, runId, "BACKTEST", bar.getOpenTime(), stepTf);
            if (sig != null) {
                StrategySignalEntity saved =
                        signalExecutionBridge.persistAndExecuteSynchronously(sig, UUID.randomUUID().toString(), "SIMULATED");
                EventStoreEntry sigTail = appendJournal(runId, uid, symbol, strategyKey, "BACKTEST_SIGNAL_GENERATED", Map.of(
                        "barOpenTime", bar.getOpenTime().toString(),
                        "signalId", saved.getId().toString()
                ));
                upsertCp(runId, uid, sigTail, metaJson(strategyKey, symbol, rangeStart, rangeEnd, seed, uid, timeframe, stepTf, i + 1, candles.size()));
            } else {
                EventStoreEntry prog = appendJournal(runId, uid, symbol, strategyKey, "BACKTEST_CANDLE_PASSED", Map.of(
                        "barOpenTime", bar.getOpenTime().toString(),
                        "index", i
                ));
                upsertCp(runId, uid, prog, metaJson(strategyKey, symbol, rangeStart, rangeEnd, seed, uid, timeframe, stepTf, i + 1, candles.size()));
            }
        }
        return backtestResultService.persistForRun(run);
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
            int totalBars
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
                    n.get("totalBars").asInt()
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
            int totalBars
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
