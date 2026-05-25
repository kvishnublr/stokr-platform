package com.stokr.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.admin.domain.MarketBackfillFailure;
import com.stokr.admin.domain.MarketBackfillGap;
import com.stokr.admin.domain.MarketBackfillJob;
import com.stokr.admin.domain.MarketBackfillJobStatus;
import com.stokr.admin.domain.MarketBackfillJobSymbol;
import com.stokr.admin.domain.MarketBackfillSymbolStatus;
import com.stokr.admin.dto.MarketBackfillCreateRequest;
import com.stokr.admin.repository.MarketBackfillFailureRepository;
import com.stokr.admin.repository.MarketBackfillGapRepository;
import com.stokr.admin.repository.MarketBackfillJobRepository;
import com.stokr.admin.repository.MarketBackfillJobSymbolRepository;
import com.stokr.common.exception.BadRequestException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.service.CandleFinalizationService;
import com.stokr.marketdata.service.MarketDataCoverageService;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.historical.BrokerHistoricalAdapterRegistry;
import com.stokr.user.broker.historical.BrokerHistoricalDataAdapter;
import com.stokr.user.broker.historical.HistoricalCandlePoint;
import com.stokr.user.broker.historical.HistoricalFetchRequest;
import com.stokr.user.broker.historical.HistoricalFetchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminMarketBackfillService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_OPEN = LocalTime.of(9, 15);
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);

    private final MarketBackfillJobRepository jobRepository;
    private final MarketBackfillJobSymbolRepository jobSymbolRepository;
    private final MarketBackfillGapRepository gapRepository;
    private final MarketBackfillFailureRepository failureRepository;
    private final MarketdataCandleRepository candleRepository;
    private final CandleFinalizationService candleFinalizationService;
    private final MarketDataCoverageService marketDataCoverageService;
    private final BrokerHistoricalAdapterRegistry historicalAdapterRegistry;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final ObjectMapper objectMapper;
    private final PlatformReadinessService platformReadinessService;
    private final PlatformMarketFeedService platformMarketFeedService;

    private static final List<String> ALLOWED_TIMEFRAMES = List.of("1m", "5m", "15m", "1h", "1d");
    private static final int MAX_FETCH_RETRIES = 3;

    @Value("${stokr.market.backfill.zerodha.chunk-minutes:1000}")
    private int zerodhaChunkMinutes;
    @Value("${stokr.market.backfill.preflight-sample-size:5}")
    private int preflightSampleSize;

    @Value("${stokr.market.universe.cache-seconds:3600}")
    private long universeCacheSeconds;
    @Value("${stokr.market.universe.nifty50-url:https://niftyindices.com/IndexConstituent/ind_nifty50list.csv}")
    private String nifty50Url;
    @Value("${stokr.market.universe.nifty100-url:https://niftyindices.com/IndexConstituent/ind_nifty100list.csv}")
    private String nifty100Url;
    @Value("${stokr.market.universe.nifty200-url:https://niftyindices.com/IndexConstituent/ind_nifty200list.csv}")
    private String nifty200Url;
    @Value("${stokr.market.universe.banknifty-url:https://niftyindices.com/IndexConstituent/ind_niftybanklist.csv}")
    private String bankNiftyUrl;
    @Value("${stokr.market.universe.finnifty-url:https://niftyindices.com/IndexConstituent/ind_niftyfinancelist.csv}")
    private String finNiftyUrl;

    private final RestClient restClient = RestClient.builder().build();
    private final Map<String, UniverseCacheRow> universeCache = new ConcurrentHashMap<>();

    @Transactional
    public UUID createAndStart(UUID adminUserId, MarketBackfillCreateRequest req) {
        validate(req);
        List<String> symbols = resolveSymbols(req.symbolGroup(), req.customSymbols());
        if (symbols.isEmpty()) {
            throw new BadRequestException("No symbols resolved for backfill job");
        }
        validateBrokerPreflight(req.brokerSource(), req.timeframe(), req.rangeStart(), req.rangeEnd(), symbols);

        MarketBackfillJob job = new MarketBackfillJob();
        job.setRequestedByUserId(adminUserId);
        job.setBrokerSource(req.brokerSource().trim().toUpperCase(Locale.ROOT));
        job.setSymbolGroup(req.symbolGroup().trim().toUpperCase(Locale.ROOT));
        job.setTimeframe(req.timeframe().trim());
        job.setRangeStart(req.rangeStart());
        job.setRangeEnd(req.rangeEnd());
        job.setStatus(MarketBackfillJobStatus.QUEUED);
        job.setTotalSymbols(symbols.size());
        job.setMessage("Queued");
        job = jobRepository.save(job);

        for (String symbol : symbols) {
            MarketBackfillJobSymbol row = new MarketBackfillJobSymbol();
            row.setJobId(job.getId());
            row.setSymbol(symbol);
            row.setStatus(MarketBackfillSymbolStatus.PENDING);
            row.setMessage("Pending");
            jobSymbolRepository.save(row);
        }

        runJobAsync(job.getId());
        return job.getId();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preflight(MarketBackfillCreateRequest req) {
        validate(req);
        List<String> symbols = resolveSymbols(req.symbolGroup(), req.customSymbols());
        List<Map<String, String>> blockers = new ArrayList<>();
        List<Map<String, String>> warnings = new ArrayList<>();

        if (symbols.isEmpty()) {
            blockers.add(block("NO_SYMBOLS_RESOLVED", "No symbols resolved for selected group.", "Select a valid group or provide CUSTOM symbols."));
        }
        if (!hasTradingSessionOverlap(req.rangeStart(), req.rangeEnd())) {
            blockers.add(block("NO_TRADING_SESSION_IN_RANGE", "Selected range has no NSE trading session overlap.", "Use weekday IST window 09:15-15:30."));
        }

        String tf = req.timeframe() == null ? "1m" : req.timeframe().trim().toLowerCase(Locale.ROOT);
        Instant lookbackCutoff = Instant.now().minus(Duration.ofDays(60));
        if ("ZERODHA".equalsIgnoreCase(req.brokerSource()) && !"1d".equals(tf) && req.rangeStart().isBefore(lookbackCutoff)) {
            blockers.add(block("LOOKBACK_EXCEEDED", "Zerodha intraday history supports limited lookback (about 60 days).", "Use recent range or import historical data."));
        }

        var readiness = platformReadinessService.snapshot();
        if (readiness.blocking()) {
            blockers.add(block("PIPELINE_BLOCKING", "Execution pipeline readiness is blocking.", "Check kill switch and execution pipeline in admin readiness."));
        }
        Map<String, Object> vendor = platformMarketFeedService.vendorSnapshot(req.brokerSource());
        boolean operationalPath = Boolean.TRUE.equals(vendor.get("operationalLivePath"));
        boolean tokenConfigured = Boolean.TRUE.equals(vendor.get("configured"));
        if (!tokenConfigured) {
            blockers.add(block("NO_PLATFORM_SESSION", "Broker platform session is not configured.", "Open Broker Infrastructure and reconnect OAuth."));
        } else if (!operationalPath) {
            warnings.add(block("PLATFORM_FEED_DEGRADED", String.valueOf(vendor.getOrDefault("operationalLivePathDetail", "Platform feed not fully operational.")),
                    "Refresh token, ensure WS connected, subscriptions > 0, and ticks flowing."));
        }

        int sampleSize = Math.max(1, Math.min(preflightSampleSize, symbols.size()));
        List<String> sampled = symbols.subList(0, sampleSize);
        try {
            validateBrokerPreflight(req.brokerSource(), req.timeframe(), req.rangeStart(), req.rangeEnd(), sampled);
        } catch (Exception ex) {
            blockers.add(block("BROKER_PREFLIGHT_FAILED", ex.getMessage(), "Fix broker/token/date-symbol issues and retry."));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", blockers.isEmpty() ? "PASS" : "FAIL");
        out.put("blockers", blockers);
        out.put("warnings", warnings);
        out.put("symbolCount", symbols.size());
        out.put("sampledSymbols", sampled);
        out.put("broker", req.brokerSource());
        out.put("timeframe", req.timeframe());
        out.put("rangeStart", req.rangeStart().toString());
        out.put("rangeEnd", req.rangeEnd().toString());
        return out;
    }

    private static Map<String, String> block(String code, String message, String action) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("action", action);
        return m;
    }

    private void validateBrokerPreflight(String brokerSource, String timeframe, Instant rangeStart, Instant rangeEnd, List<String> symbols) {
        BrokerHistoricalDataAdapter adapter = historicalAdapterRegistry.require(brokerSource.trim().toUpperCase(Locale.ROOT));
            if (!hasTradingSessionOverlap(rangeStart, rangeEnd)) {
            throw new BadRequestException("Backfill window has no NSE trading-session overlap (Mon-Fri, 09:15-15:30 IST).");
        }
        // Probe near rangeEnd (active session) — Zerodha often returns empty for a 5m window at rangeStart.
        Instant probeEnd = rangeEnd.minus(Duration.ofHours(1));
        if (!probeEnd.isAfter(rangeStart)) {
            probeEnd = rangeStart.plus(Duration.ofMinutes(5));
        }
        if (!probeEnd.isBefore(rangeEnd)) {
            probeEnd = rangeEnd;
        }
        Instant probeStart = probeEnd.minus(Duration.ofMinutes(5));
        if (probeStart.isBefore(rangeStart)) {
            probeStart = rangeStart;
        }
        int sample = Math.max(1, Math.min(preflightSampleSize, symbols.size()));
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < sample; i++) {
            String symbol = symbols.get(i);
            HistoricalFetchResult probe = adapter.fetch(new HistoricalFetchRequest(symbol, timeframe, probeStart, probeEnd));
            if (!probe.success() || probe.candles().isEmpty()) {
                String detail = probe.detail() == null ? "" : probe.detail().replaceAll("\\s+", " ").trim();
                if (probe.success() && probe.candles().isEmpty()) {
                    detail = "No candles in probe window (likely non-trading window or symbol not available).";
                }
                if (detail.length() > 140) {
                    detail = detail.substring(0, 140) + "...";
                }
                String auth = probe.authSource() == null || probe.authSource().isBlank() ? "unknown-auth" : probe.authSource();
                String code = probe.success() && probe.candles().isEmpty() ? "NO_CANDLES_IN_PROBE" : probe.code();
                failed.add(symbol + ":" + code + " (" + auth + ") " + detail);
            }
        }
        if (!failed.isEmpty()) {
            String head = failed.stream().limit(3).reduce((a, b) -> a + ", " + b).orElse("unknown");
            throw new BadRequestException("Backfill preflight failed (" + failed.size() + "/" + sample + "): " + head);
        }
    }

    @Async("taskExecutor")
    public void runJobAsync(UUID jobId) {
        MarketBackfillJob job = jobRepository.findByIdAndDeletedFalse(jobId)
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        Instant started = Instant.now();
        job.setStatus(MarketBackfillJobStatus.RUNNING);
        job.setMessage("Running");
        jobRepository.save(job);

        List<MarketBackfillJobSymbol> symbols = jobSymbolRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId);
        BrokerHistoricalDataAdapter adapter = historicalAdapterRegistry.require(job.getBrokerSource());
        String targetTf = job.getTimeframe();
        String sourceTf = "1m";

        int processed = 0;
        int failures = 0;
        int totalGaps = 0;
        long totalCandles = 0L;
        Instant latestCandle = null;

        for (MarketBackfillJobSymbol row : symbols) {
            if (isCancelled(jobId)) {
                markCancelled(jobId, "Cancelled by admin");
                return;
            }
            processed++;
            boolean alreadyReady = marketDataCoverageService.isRangeAlreadyReady(
                    row.getSymbol(),
                    sourceTf,
                    job.getRangeStart(),
                    job.getRangeEnd()
            );
            if (alreadyReady) {
                row.setStatus(MarketBackfillSymbolStatus.SKIPPED);
                row.setMessage("Historical coverage already available");
                jobSymbolRepository.save(row);
                updateProgress(jobId, processed, failures, totalCandles, totalGaps, latestCandle);
                continue;
            }
            row.setMessage("Fetching from " + adapter.brokerCode());
            jobSymbolRepository.save(row);

            HistoricalFetchResult fetch = fetchChunkedWithRetry(adapter, row.getSymbol(), sourceTf, job.getRangeStart(), job.getRangeEnd());
            String authSource = fetch.authSource() == null || fetch.authSource().isBlank() ? "UNKNOWN" : fetch.authSource();
            if (!fetch.success() && fetch.candles().isEmpty()) {
                failures++;
                row.setStatus(MarketBackfillSymbolStatus.FAILED);
                row.setFailureCount(row.getFailureCount() + 1);
                row.setMessage(fetch.code() + ": " + fetch.detail() + " · auth " + authSource);
                jobSymbolRepository.save(row);
                saveFailure(jobId, row.getSymbol(), fetch.code(), fetch.detail() + " · auth " + authSource, isRetryableCode(fetch.code()));
                updateProgress(jobId, processed, failures, totalCandles, totalGaps, latestCandle);
                continue;
            }

            long saved = persistCandles(row.getSymbol(), sourceTf, fetch.candles());
            totalCandles += saved;
            row.setCandlesFetched(saved);
            if (!fetch.candles().isEmpty()) {
                latestCandle = maxInstant(latestCandle, fetch.candles().get(fetch.candles().size() - 1).openTime());
                row.setLatestCandleAt(latestCandle);
            }

            List<CandleFinalizationService.CandleGap> gaps = candleFinalizationService.detectGapsMarketHoursAware(
                    row.getSymbol(),
                    sourceTf,
                    job.getRangeStart(),
                    job.getRangeEnd()
            );
            if (!fetch.success()) {
                failures++;
                row.setStatus(MarketBackfillSymbolStatus.FAILED);
                row.setFailureCount(row.getFailureCount() + 1);
                row.setMessage(fetch.code() + ": " + fetch.detail() + " (partial candles persisted) · auth " + authSource);
                saveFailure(jobId, row.getSymbol(), fetch.code(), fetch.detail() + " · auth " + authSource, isRetryableCode(fetch.code()));
            } else if (!gaps.isEmpty()) {
                row.setStatus(MarketBackfillSymbolStatus.GAP_DETECTED);
                row.setGapCount(gaps.size());
                row.setMessage("Detected " + gaps.size() + " gap(s)");
                totalGaps += gaps.size();
                for (var g : gaps) {
                    MarketBackfillGap gap = new MarketBackfillGap();
                    gap.setJobId(jobId);
                    gap.setSymbol(row.getSymbol());
                    gap.setTimeframe(sourceTf);
                    gap.setGapStart(g.afterOpen());
                    gap.setGapEnd(g.nextOpen());
                    long periodMs = timeframeMillis(sourceTf);
                    int missingBars = (int) Math.max(1L, g.excessMillis() / periodMs);
                    gap.setMissingBars(missingBars);
                    gap.setRepaired(false);
                    gap.setMessage("Continuity gap detected");
                    gapRepository.save(gap);
                }
            } else {
                row.setStatus(MarketBackfillSymbolStatus.FETCHED);
                row.setMessage("Fetched · auth " + authSource);
            }
            var coverage = marketDataCoverageService.validateAndUpsert(
                    row.getSymbol(),
                    sourceTf,
                    job.getRangeStart(),
                    job.getRangeEnd()
            );
            row.setMessage(row.getMessage() + " · " + coverage.replayReadiness());
            jobSymbolRepository.save(row);

            if (!"1m".equals(targetTf)) {
                int derived = deriveFromOneMinute(row.getSymbol(), targetTf, job.getRangeStart(), job.getRangeEnd());
                row.setMessage(row.getMessage() + " · derived " + derived + " " + targetTf + " candles");
                jobSymbolRepository.save(row);
            }
            updateProgress(jobId, processed, failures, totalCandles, totalGaps, latestCandle);
        }

        MarketBackfillJob done = jobRepository.findByIdAndDeletedFalse(jobId).orElseThrow();
        done.setProcessedSymbols(processed);
        done.setFailureCount(failures);
        done.setTotalCandlesFetched(totalCandles);
        done.setTotalGaps(totalGaps);
        done.setLatestCandleAt(latestCandle);
        done.setThroughputCandlesPerSec(throughput(totalCandles, started, Instant.now()));
        if (done.isCancelled()) {
            done.setStatus(MarketBackfillJobStatus.CANCELLED);
            done.setMessage("Cancelled by admin");
        } else if (failures > 0 || totalGaps > 0) {
            done.setStatus(MarketBackfillJobStatus.PARTIAL);
            done.setMessage(buildPartialSummary(jobId, failures, totalGaps));
        } else {
            done.setStatus(MarketBackfillJobStatus.COMPLETED);
            done.setMessage("Completed");
        }
        jobRepository.save(done);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listJobs(int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MarketBackfillJob j : jobRepository.findTop50ByDeletedFalseOrderByUpdatedAtDesc().stream().limit(n).toList()) {
            out.add(toJobRow(j));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> jobDetail(UUID jobId) {
        MarketBackfillJob job = jobRepository.findByIdAndDeletedFalse(jobId)
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        Map<String, Object> out = toJobRow(job);
        out.put("symbols", jobSymbolRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", s.getSymbol());
            m.put("status", s.getStatus().name());
            m.put("candlesFetched", s.getCandlesFetched());
            m.put("gapCount", s.getGapCount());
            m.put("failureCount", s.getFailureCount());
            m.put("latestCandleAt", s.getLatestCandleAt() != null ? s.getLatestCandleAt().toString() : null);
            m.put("message", s.getMessage());
            return m;
        }).toList());
        out.put("gaps", gapRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId).stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", g.getSymbol());
            m.put("timeframe", g.getTimeframe());
            m.put("gapStart", g.getGapStart().toString());
            m.put("gapEnd", g.getGapEnd().toString());
            m.put("missingBars", g.getMissingBars());
            m.put("repaired", g.isRepaired());
            return m;
        }).toList());
        out.put("failures", failureRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId).stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", f.getSymbol());
            m.put("failureCode", f.getFailureCode());
            m.put("message", f.getMessage());
            m.put("retryable", f.isRetryable());
            m.put("attemptCount", f.getAttemptCount());
            m.put("lastOccurredAt", f.getLastOccurredAt() != null ? f.getLastOccurredAt().toString() : null);
            return m;
        }).toList());
        return out;
    }

    @Transactional
    public void cancel(UUID jobId) {
        MarketBackfillJob job = jobRepository.findByIdAndDeletedFalse(jobId)
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        job.setCancelled(true);
        if (job.getStatus() == MarketBackfillJobStatus.QUEUED) {
            job.setStatus(MarketBackfillJobStatus.CANCELLED);
            job.setMessage("Cancelled by admin before start");
        }
        jobRepository.save(job);
    }

    @Transactional
    public UUID retryFailures(UUID jobId) {
        MarketBackfillJob src = jobRepository.findByIdAndDeletedFalse(jobId)
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        List<String> failedSymbols = jobSymbolRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId).stream()
                .filter(s -> s.getStatus() == MarketBackfillSymbolStatus.FAILED || s.getStatus() == MarketBackfillSymbolStatus.GAP_DETECTED)
                .map(MarketBackfillJobSymbol::getSymbol)
                .distinct()
                .toList();
        if (failedSymbols.isEmpty()) {
            throw new BadRequestException("No failed symbols to retry");
        }
        UUID newJobId = createAndStart(
                src.getRequestedByUserId(),
                new MarketBackfillCreateRequest(
                        src.getBrokerSource(),
                        "CUSTOM",
                        failedSymbols,
                        src.getTimeframe(),
                        src.getRangeStart(),
                        src.getRangeEnd()
                )
        );
        return newJobId;
    }

    @Transactional
    public UUID repairGaps(UUID jobId) {
        MarketBackfillJob src = jobRepository.findByIdAndDeletedFalse(jobId)
                .orElseThrow(() -> new NotFoundException("Backfill job not found"));
        List<String> symbols = gapRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId).stream()
                .filter(g -> !g.isRepaired())
                .map(MarketBackfillGap::getSymbol)
                .distinct()
                .toList();
        if (symbols.isEmpty()) {
            throw new BadRequestException("No unrepaired gaps found");
        }
        UUID newJobId = createAndStart(
                src.getRequestedByUserId(),
                new MarketBackfillCreateRequest(
                        src.getBrokerSource(),
                        "CUSTOM",
                        symbols,
                        src.getTimeframe(),
                        src.getRangeStart(),
                        src.getRangeEnd()
                )
        );
        src.setRepairState("REPAIR_TRIGGERED");
        jobRepository.save(src);
        return newJobId;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> capabilityMatrix() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> groups = List.of("NIFTY_50", "NIFTY_100", "NIFTY_200", "BANKNIFTY", "FINNIFTY", "ALL_ACTIVE_STRATEGY_SYMBOLS", "ALL_EQUITY", "CUSTOM");
        m.put("brokers", historicalAdapterRegistry.capabilityMatrix());
        m.put("timeframes", ALLOWED_TIMEFRAMES);
        m.put("symbolGroups", groups);
        m.put("sourceOfTruth", "marketdata_candles:1m");
        m.put("derivedTimeframes", List.of("5m", "15m", "1h", "1d"));
        m.put("coverage", marketDataCoverageService.recentCoverage());
        Map<String, Object> preview = new LinkedHashMap<>();
        for (String g : groups) {
            if ("CUSTOM".equals(g)) {
                preview.put(g, Map.of("count", 0, "symbols", List.of()));
                continue;
            }
            List<String> symbols = resolveSymbols(g, List.of());
            preview.put(g, Map.of(
                    "count", symbols.size(),
                    "symbols", symbols
            ));
        }
        m.put("symbolGroupPreview", preview);
        return m;
    }

    @Transactional
    public Map<String, Object> readinessAuthority(String symbol, String timeframe, Instant from, Instant to, String useCase) {
        var a = marketDataCoverageService.assessReadiness(
                symbol,
                timeframe,
                from,
                to,
                useCase == null || useCase.isBlank() ? "REPLAY" : useCase.trim().toUpperCase(Locale.ROOT),
                true
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", a.symbol());
        out.put("timeframe", a.timeframe());
        out.put("useCase", a.useCase());
        out.put("ready", a.ready());
        out.put("state", a.state());
        out.put("detail", a.detail());
        out.put("latestCandleAt", a.latestCandleAt() != null ? a.latestCandleAt().toString() : null);
        out.put("freshnessAgeSeconds", a.freshnessAgeSeconds());
        out.put("coverageStart", a.coverageStart() != null ? a.coverageStart().toString() : null);
        out.put("coverageEnd", a.coverageEnd() != null ? a.coverageEnd().toString() : null);
        return out;
    }

    @Transactional
    public Map<String, Object> importCsv(UUID adminUserId, String symbol, String timeframe, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("CSV file is required");
        }
        String tf = timeframe == null || timeframe.isBlank() ? "1m" : timeframe.trim();
        if (!ALLOWED_TIMEFRAMES.contains(tf)) {
            throw new BadRequestException("Unsupported timeframe: " + tf);
        }
        String fallbackSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);

        Map<String, List<HistoricalCandlePoint>> bySymbol = new LinkedHashMap<>();
        int rowsRead = 0;
        int rowsAccepted = 0;
        int rowsRejected = 0;
        int parseErrors = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new BadRequestException("CSV is empty");
            }
            String[] headers = splitCsvLine(headerLine);
            Map<String, Integer> idx = headerIndex(headers);

            Integer tsIdx = firstIndex(idx, "timestamp", "time", "open_time", "datetime", "date");
            Integer openIdx = firstIndex(idx, "open", "o");
            Integer highIdx = firstIndex(idx, "high", "h");
            Integer lowIdx = firstIndex(idx, "low", "l");
            Integer closeIdx = firstIndex(idx, "close", "c");
            Integer volIdx = firstIndex(idx, "volume", "v", "vol");
            Integer symIdx = firstIndex(idx, "symbol", "tradingsymbol", "ticker");

            if (tsIdx == null || openIdx == null || highIdx == null || lowIdx == null || closeIdx == null) {
                throw new BadRequestException("CSV must include: timestamp, open, high, low, close (volume optional; symbol optional if form symbol provided).");
            }

            String line;
            while ((line = reader.readLine()) != null) {
                rowsRead++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    String[] cols = splitCsvLine(line);
                    String sym = readCol(cols, symIdx);
                    if (sym == null || sym.isBlank()) {
                        sym = fallbackSymbol;
                    }
                    sym = sym == null ? "" : sym.trim().toUpperCase(Locale.ROOT);
                    if (sym.isBlank()) {
                        rowsRejected++;
                        continue;
                    }
                    Instant ts = parseFlexibleInstant(readCol(cols, tsIdx));
                    if (ts == null) {
                        rowsRejected++;
                        parseErrors++;
                        continue;
                    }
                    BigDecimal open = parseDecimal(readCol(cols, openIdx));
                    BigDecimal high = parseDecimal(readCol(cols, highIdx));
                    BigDecimal low = parseDecimal(readCol(cols, lowIdx));
                    BigDecimal close = parseDecimal(readCol(cols, closeIdx));
                    BigDecimal volume = parseDecimal(readCol(cols, volIdx));
                    if (open == null || high == null || low == null || close == null) {
                        rowsRejected++;
                        parseErrors++;
                        continue;
                    }
                    HistoricalCandlePoint p = new HistoricalCandlePoint(ts, open, high, low, close, volume == null ? BigDecimal.ZERO : volume);
                    bySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(p);
                    rowsAccepted++;
                } catch (Exception ex) {
                    rowsRejected++;
                    parseErrors++;
                }
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Failed to read CSV: " + ex.getMessage());
        }

        if (bySymbol.isEmpty()) {
            throw new BadRequestException("No valid candle rows found in CSV.");
        }

        long totalPersisted = 0L;
        int symbolsUpdated = 0;
        List<Map<String, Object>> symbolSummary = new ArrayList<>();

        for (Map.Entry<String, List<HistoricalCandlePoint>> e : bySymbol.entrySet()) {
            String sym = e.getKey();
            List<HistoricalCandlePoint> points = e.getValue();
            points.sort(java.util.Comparator.comparing(HistoricalCandlePoint::openTime));
            long saved = persistCandles(sym, tf, points);
            totalPersisted += saved;
            symbolsUpdated++;

            Instant from = points.get(0).openTime();
            Instant to = points.get(points.size() - 1).openTime();
            var coverage = marketDataCoverageService.validateAndUpsert(sym, tf, from, to);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", sym);
            row.put("timeframe", tf);
            row.put("rows", points.size());
            row.put("persisted", saved);
            row.put("rangeStart", from.toString());
            row.put("rangeEnd", to.toString());
            row.put("replayReadiness", coverage.replayReadiness());
            row.put("scannerReadiness", coverage.scannerReadiness());
            symbolSummary.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("importedBy", adminUserId != null ? adminUserId.toString() : null);
        out.put("fileName", file.getOriginalFilename());
        out.put("timeframe", tf);
        out.put("rowsRead", rowsRead);
        out.put("rowsAccepted", rowsAccepted);
        out.put("rowsRejected", rowsRejected);
        out.put("parseErrors", parseErrors);
        out.put("symbolsUpdated", symbolsUpdated);
        out.put("totalPersisted", totalPersisted);
        out.put("symbols", symbolSummary);
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> strategyReadinessAudit(Instant from, Instant to, String timeframe, String useCase) {
        String tf = timeframe == null || timeframe.isBlank() ? "1m" : timeframe.trim();
        String uc = useCase == null || useCase.isBlank() ? "REPLAY" : useCase.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();

        strategyDefinitionRepository.findAllByDeletedFalseAndEnabledTrueAndVisibleToUsersTrue(org.springframework.data.domain.Pageable.ofSize(500))
                .forEach(def -> {
                    List<String> symbols = resolveStrategySymbols(def);
                    List<Map<String, Object>> blockers = new ArrayList<>();
                    int readyCount = 0;
                    for (String symbol : symbols) {
                        var a = marketDataCoverageService.assessReadiness(symbol, tf, from, to, uc, true);
                        if (a.ready()) {
                            readyCount++;
                            continue;
                        }
                        blockers.add(Map.of(
                                "symbol", symbol,
                                "state", a.state(),
                                "detail", a.detail()
                        ));
                    }

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("strategyKey", def.getStrategyKey());
                    row.put("displayName", def.getDisplayName());
                    row.put("timeframe", tf);
                    row.put("useCase", uc);
                    row.put("symbolsTotal", symbols.size());
                    row.put("symbolsReady", readyCount);
                    row.put("ready", blockers.isEmpty() && !symbols.isEmpty());
                    row.put("status", blockers.isEmpty() && !symbols.isEmpty() ? "READY" : "BLOCKED");
                    row.put("blockers", blockers);
                    row.put("symbols", symbols);
                    out.add(row);
                });
        return out;
    }

    private Map<String, Object> toJobRow(MarketBackfillJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId().toString());
        m.put("status", j.getStatus().name());
        m.put("brokerSource", j.getBrokerSource());
        m.put("symbolGroup", j.getSymbolGroup());
        m.put("timeframe", j.getTimeframe());
        m.put("rangeStart", j.getRangeStart().toString());
        m.put("rangeEnd", j.getRangeEnd().toString());
        m.put("processedSymbols", j.getProcessedSymbols());
        m.put("totalSymbols", j.getTotalSymbols());
        m.put("totalCandlesFetched", j.getTotalCandlesFetched());
        m.put("totalGaps", j.getTotalGaps());
        m.put("failureCount", j.getFailureCount());
        m.put("latestCandleAt", j.getLatestCandleAt() != null ? j.getLatestCandleAt().toString() : null);
        m.put("throughputCps", j.getThroughputCandlesPerSec() != null ? j.getThroughputCandlesPerSec().toPlainString() : null);
        m.put("message", j.getMessage());
        m.put("updatedAt", j.getUpdatedAt() != null ? j.getUpdatedAt().toString() : null);
        return m;
    }

    private void validate(MarketBackfillCreateRequest req) {
        String tf = req.timeframe().trim();
        if (!ALLOWED_TIMEFRAMES.contains(tf)) {
            throw new BadRequestException("Unsupported timeframe: " + tf);
        }
        if (!req.rangeStart().isBefore(req.rangeEnd())) {
            throw new BadRequestException("rangeStart must be before rangeEnd");
        }
        Instant now = Instant.now();
        if (!req.rangeEnd().isBefore(now)) {
            throw new BadRequestException("rangeEnd must be in the past (use completed market candles only)");
        }
        long spanDays = Duration.between(req.rangeStart(), req.rangeEnd()).toDays();
        if ("1m".equals(tf) && spanDays > 180) {
            throw new BadRequestException("1m backfill window too large. Use at most 180 days per launch.");
        }
        if ("5m".equals(tf) && spanDays > 365) {
            throw new BadRequestException("5m backfill window too large. Use at most 365 days per launch.");
        }
        if ("15m".equals(tf) && spanDays > 730) {
            throw new BadRequestException("15m backfill window too large. Use at most 730 days per launch.");
        }
        if ("1h".equals(tf) && spanDays > 1460) {
            throw new BadRequestException("1h backfill window too large. Use at most 1460 days per launch.");
        }
        if ("ZERODHA".equalsIgnoreCase(req.brokerSource()) && !"1d".equals(tf)) {
            Instant lookbackCutoff = Instant.now().minus(Duration.ofDays(60));
            if (req.rangeStart().isBefore(lookbackCutoff)) {
                throw new BadRequestException("Zerodha intraday history supports only recent windows (about 60 days). Select a newer start date or use historical import.");
            }
        }
        validateTradingWindow(req.rangeStart(), "rangeStart");
        validateTradingWindow(req.rangeEnd(), "rangeEnd");
    }

    private void validateTradingWindow(Instant instant, String field) {
        ZonedDateTime ist = instant.atZone(IST);
        DayOfWeek dow = ist.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            throw new BadRequestException(field + " must be a market weekday (Mon-Fri) in IST");
        }
        LocalTime t = ist.toLocalTime();
        if (t.isBefore(NSE_OPEN) || t.isAfter(NSE_CLOSE)) {
            throw new BadRequestException(field + " must be between 09:15 and 15:30 IST");
        }
    }

    private List<String> resolveSymbols(String group, List<String> custom) {
        String g = group == null ? "" : group.trim().toUpperCase(Locale.ROOT);
        if ("CUSTOM".equals(g)) {
            if (custom == null) {
                return List.of();
            }
            return custom.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .distinct()
                    .toList();
        }
        if ("NIFTY_50".equals(g)) {
            return loadUniverse("NIFTY_50", nifty50Url, fallbackNifty50());
        }
        if ("BANKNIFTY".equals(g)) {
            return loadUniverse("BANKNIFTY", bankNiftyUrl, fallbackBankNifty());
        }
        if ("FINNIFTY".equals(g)) {
            return loadUniverse("FINNIFTY", finNiftyUrl, fallbackFinNifty());
        }
        if ("ALL_ACTIVE_STRATEGY_SYMBOLS".equals(g)) {
            return resolveStrategySymbols();
        }
        if ("NIFTY_100".equals(g)) {
            return loadUniverse("NIFTY_100", nifty100Url, fallbackNifty100());
        }
        if ("NIFTY_200".equals(g)) {
            return loadUniverse("NIFTY_200", nifty200Url, fallbackNifty200());
        }
        if ("ALL_EQUITY".equals(g)) {
            return loadUniverse("NIFTY_200", nifty200Url, fallbackNifty200());
        }
        throw new BadRequestException("Unsupported symbolGroup: " + g);
    }

    private List<String> loadUniverse(String key, String url, List<String> fallback) {
        UniverseCacheRow cached = universeCache.get(key);
        if (cached != null && Duration.between(cached.loadedAt(), Instant.now()).getSeconds() < universeCacheSeconds && !cached.symbols().isEmpty()) {
            return cached.symbols();
        }
        try {
            String csv = restClient.get().uri(url).retrieve().body(String.class);
            List<String> parsed = parseSymbolsFromCsv(csv);
            if (!parsed.isEmpty()) {
                universeCache.put(key, new UniverseCacheRow(parsed, Instant.now()));
                return parsed;
            }
        } catch (Exception ex) {
            log.warn("market.universe.fetch_failed group={} url={} err={}", key, url, ex.toString());
        }
        universeCache.put(key, new UniverseCacheRow(fallback, Instant.now()));
        return fallback;
    }

    private List<String> parseSymbolsFromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] lines = csv.split("\\R");
        if (lines.length < 2) {
            return List.of();
        }
        String[] hdr = lines[0].split(",");
        int symbolIdx = -1;
        for (int i = 0; i < hdr.length; i++) {
            String h = hdr[i].replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if ("SYMBOL".equals(h)) {
                symbolIdx = i;
                break;
            }
        }
        if (symbolIdx < 0) {
            return List.of();
        }
        Set<String> out = new HashSet<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] row = line.split(",");
            if (row.length <= symbolIdx) {
                continue;
            }
            String s = row[symbolIdx].replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out.stream().sorted().toList();
    }

    private static List<String> fallbackNifty50() {
        return List.of("RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "INFY", "ITC", "LT", "SBIN", "BHARTIARTL", "HINDUNILVR");
    }

    private static List<String> fallbackNifty100() {
        return List.of(
                "RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "INFY", "ITC", "LT", "SBIN", "BHARTIARTL", "HINDUNILVR",
                "KOTAKBANK", "AXISBANK", "BAJFINANCE", "MARUTI", "ASIANPAINT", "HCLTECH", "SUNPHARMA", "TITAN",
                "ULTRACEMCO", "NESTLEIND", "WIPRO", "POWERGRID", "NTPC", "ONGC", "M&M", "ADANIENT", "ADANIPORTS",
                "TATASTEEL", "JSWSTEEL", "COALINDIA", "TECHM", "INDUSINDBK", "BAJAJFINSV", "HINDALCO", "GRASIM",
                "CIPLA", "DRREDDY", "EICHERMOT", "BPCL", "DIVISLAB", "APOLLOHOSP", "BRITANNIA", "HEROMOTOCO",
                "TATAMOTORS", "SBILIFE", "HDFCLIFE", "PIDILITIND", "SIEMENS", "HAVELLS", "DLF", "GODREJCP",
                "AMBUJACEM", "ICICIPRULI", "ICICIGI", "BANKBARODA", "PNB", "CANBK", "AUBANK", "IDFCFIRSTB",
                "FEDERALBNK", "LICI", "HAL", "BEL", "IRCTC", "ZOMATO", "DMART", "NAUKRI", "PAYTM", "POLICYBZR",
                "TRENT", "JIOFIN", "VBL", "TORNTPHARM", "ABB", "SHREECEM", "DABUR", "MARICO", "COLPAL", "BERGEPAINT",
                "INDIGO", "MUTHOOTFIN", "CHOLAFIN", "BOSCHLTD", "MRF", "PAGEIND", "LTIM", "PERSISTENT", "COFORGE",
                "MPHASIS", "OFSS", "TATAELXSI", "LODHA", "ADANIGREEN", "ADANIENSOL", "TATAPOWER", "GAIL", "IOC",
                "VEDL", "JINDALSTEL", "SAIL", "NMDC", "BHEL", "RECLTD", "PFC", "IRFC", "NHPC", "SUZLON"
        );
    }

    private static List<String> fallbackNifty200() {
        return fallbackNifty100();
    }

    private static List<String> fallbackBankNifty() {
        return List.of("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BANKBARODA", "PNB", "CANBK", "AUBANK", "IDFCFIRSTB", "FEDERALBNK");
    }

    private static List<String> fallbackFinNifty() {
        return List.of("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "BAJFINANCE", "BAJAJFINSV", "HDFCLIFE", "SBILIFE", "HDFCAMC");
    }

    private record UniverseCacheRow(List<String> symbols, Instant loadedAt) {
    }

    private boolean isCancelled(UUID jobId) {
        return jobRepository.findByIdAndDeletedFalse(jobId).map(MarketBackfillJob::isCancelled).orElse(true);
    }

    private void markCancelled(UUID jobId, String message) {
        MarketBackfillJob job = jobRepository.findByIdAndDeletedFalse(jobId).orElseThrow();
        job.setStatus(MarketBackfillJobStatus.CANCELLED);
        job.setMessage(message);
        jobRepository.save(job);
    }

    @Transactional
    protected long persistCandles(String symbol, String timeframe, List<HistoricalCandlePoint> points) {
        long n = 0;
        for (HistoricalCandlePoint p : points) {
            if (p.openTime() == null) {
                continue;
            }
            MarketdataCandle c = candleRepository
                    .findBySymbolAndTimeframeAndOpenTimeAndDeletedFalse(symbol, timeframe, p.openTime())
                    .orElseGet(MarketdataCandle::new);
            c.setSymbol(symbol);
            c.setTimeframe(timeframe);
            c.setOpenTime(p.openTime());
            c.setOpenPrice(scale(p.openPrice()));
            c.setHighPrice(scale(p.highPrice()));
            c.setLowPrice(scale(p.lowPrice()));
            c.setClosePrice(scale(p.closePrice()));
            c.setVolume(scale(p.volume()));
            candleRepository.save(c);
            n++;
        }
        return n;
    }

    @Transactional
    protected int deriveFromOneMinute(String symbol, String targetTf, Instant start, Instant end) {
        List<MarketdataCandle> oneMin = candleRepository
                .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(symbol, "1m", start, end);
        if (oneMin.isEmpty()) {
            return 0;
        }
        Map<Instant, List<MarketdataCandle>> buckets = new LinkedHashMap<>();
        for (MarketdataCandle c : oneMin) {
            Instant bucketStart = bucketStart(c.getOpenTime(), targetTf);
            buckets.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(c);
        }
        int out = 0;
        for (var e : buckets.entrySet()) {
            List<MarketdataCandle> rows = e.getValue();
            rows.sort(java.util.Comparator.comparing(MarketdataCandle::getOpenTime));
            MarketdataCandle agg = candleRepository
                    .findBySymbolAndTimeframeAndOpenTimeAndDeletedFalse(symbol, targetTf, e.getKey())
                    .orElseGet(MarketdataCandle::new);
            agg.setSymbol(symbol);
            agg.setTimeframe(targetTf);
            agg.setOpenTime(e.getKey());
            agg.setOpenPrice(rows.get(0).getOpenPrice());
            BigDecimal high = rows.stream().map(MarketdataCandle::getHighPrice).filter(v -> v != null).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal low = rows.stream().map(MarketdataCandle::getLowPrice).filter(v -> v != null).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal vol = rows.stream().map(MarketdataCandle::getVolume).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
            agg.setHighPrice(high);
            agg.setLowPrice(low);
            agg.setClosePrice(rows.get(rows.size() - 1).getClosePrice());
            agg.setVolume(vol);
            candleRepository.save(agg);
            out++;
        }
        return out;
    }

    private void updateProgress(UUID jobId, int processed, int failures, long candles, int gaps, Instant latestCandle) {
        MarketBackfillJob job = jobRepository.findByIdAndDeletedFalse(jobId).orElseThrow();
        job.setProcessedSymbols(processed);
        job.setFailureCount(failures);
        job.setTotalCandlesFetched(candles);
        job.setTotalGaps(gaps);
        job.setLatestCandleAt(latestCandle);
        job.setMessage("Running");
        jobRepository.save(job);
    }

    private void saveFailure(UUID jobId, String symbol, String code, String detail, boolean retryable) {
        String failureCode = code != null ? code : "ERROR";
        MarketBackfillFailure f = failureRepository
                .findByJobIdAndSymbolAndFailureCodeAndDeletedFalse(jobId, symbol, failureCode)
                .orElseGet(MarketBackfillFailure::new);
        if (f.getId() == null) {
            f.setJobId(jobId);
            f.setSymbol(symbol);
            f.setFailureCode(failureCode);
            f.setAttemptCount(0);
        }
        f.setMessage(detail);
        f.setRetryable(retryable);
        f.setAttemptCount(f.getAttemptCount() + 1);
        f.setLastOccurredAt(Instant.now());
        failureRepository.save(f);
    }

    private String buildPartialSummary(UUID jobId, int failures, int gaps) {
        List<MarketBackfillFailure> all = failureRepository.findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(jobId);
        String topCode = all.isEmpty() ? null : all.get(0).getFailureCode();
        if (topCode == null || topCode.isBlank()) {
            return "Completed with " + failures + " failures, " + gaps + " gaps";
        }
        return "Completed with " + failures + " failures, " + gaps + " gaps · top reason " + topCode;
    }

    private List<String> resolveStrategySymbols() {
        Set<String> out = new HashSet<>();
        strategyDefinitionRepository.findAllByDeletedFalseAndEnabledTrueAndVisibleToUsersTrue(org.springframework.data.domain.Pageable.ofSize(200))
                .forEach(def -> {
                    String cfg = def.getConfigJson();
                    if (cfg == null || cfg.isBlank()) {
                        return;
                    }
                    try {
                        JsonNode root = objectMapper.readTree(cfg);
                        JsonNode symbols = root.path("symbols");
                        if (symbols.isArray()) {
                            symbols.forEach(n -> {
                                String s = n.asText("").trim();
                                if (!s.isBlank()) {
                                    out.add(s);
                                }
                            });
                        }
                    } catch (Exception ignored) {
                    }
                });
        if (out.isEmpty()) {
            return List.of("NIFTY_FUT", "BANKNIFTY_FUT");
        }
        return out.stream().sorted().toList();
    }

    private List<String> resolveStrategySymbols(com.stokr.strategy.domain.StrategyDefinition def) {
        Set<String> out = new HashSet<>();
        String cfg = def.getConfigJson();
        if (cfg != null && !cfg.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(cfg);
                JsonNode symbols = root.path("symbols");
                if (symbols.isArray()) {
                    symbols.forEach(n -> {
                        String s = n.asText("").trim();
                        if (!s.isBlank()) {
                            out.add(s.toUpperCase(Locale.ROOT));
                        }
                    });
                }
                String single = root.path("symbol").asText("").trim();
                if (!single.isBlank()) {
                    out.add(single.toUpperCase(Locale.ROOT));
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            return List.of("NIFTY_FUT", "BANKNIFTY_FUT");
        }
        return out.stream().sorted().toList();
    }

    private HistoricalFetchResult fetchChunkedWithRetry(
            BrokerHistoricalDataAdapter adapter,
            String symbol,
            String timeframe,
            Instant rangeStart,
            Instant rangeEnd
    ) {
        BrokerHistoricalDataAdapter useAdapter = adapter;
        if (!"ZERODHA".equalsIgnoreCase(adapter.brokerCode())) {
            return fetchWithRetry(useAdapter, symbol, timeframe, rangeStart, rangeEnd);
        }
        List<TimeWindow> windows = tradingWindows(rangeStart, rangeEnd);
        if (windows.isEmpty()) {
            return HistoricalFetchResult.fail("NO_TRADING_SESSION_IN_RANGE", "Selected range has no trading session overlap");
        }
        List<HistoricalCandlePoint> merged = new ArrayList<>();
        boolean hadChunkIssues = false;
        String lastChunkIssue = null;
        for (TimeWindow w : windows) {
            Instant cursor = w.start();
            while (cursor.isBefore(w.end())) {
                Instant chunkEnd = cursor.plus(Duration.ofMinutes(Math.max(60, zerodhaChunkMinutes)));
                if (chunkEnd.isAfter(w.end())) {
                    chunkEnd = w.end();
                }
                HistoricalFetchResult chunk = fetchWithRetry(useAdapter, symbol, timeframe, cursor, chunkEnd);
                if (!chunk.success()) {
                    if (merged.isEmpty()) {
                        return chunk;
                    }
                    hadChunkIssues = true;
                    lastChunkIssue = chunk.code() + ":" + chunk.detail();
                    cursor = chunkEnd;
                    continue;
                }
                if (chunk.candles().isEmpty()) {
                    hadChunkIssues = true;
                    lastChunkIssue = "EMPTY_CHUNK";
                    cursor = chunkEnd;
                    continue;
                }
                merged.addAll(chunk.candles());
                Instant nextCursor = chunk.candles().get(chunk.candles().size() - 1).openTime().plusSeconds(60);
                if (!nextCursor.isAfter(cursor)) {
                    hadChunkIssues = true;
                    lastChunkIssue = "NON_ADVANCING_CURSOR";
                    cursor = chunkEnd;
                    continue;
                }
                cursor = nextCursor;
            }
        }
        if (merged.isEmpty()) {
            return HistoricalFetchResult.fail("NO_CANDLES_RETURNED", "No candles returned in trading sessions for selected range");
        }
        if (hadChunkIssues) {
            log.warn("market.backfill.partial {} {} {} candles={} lastIssue={}",
                    adapter.brokerCode(), symbol, timeframe, merged.size(), lastChunkIssue);
            return HistoricalFetchResult.failWithCandles(
                    "PARTIAL_FETCH",
                    lastChunkIssue == null ? "Partial chunk fetch" : "Partial chunk fetch: " + lastChunkIssue,
                    merged
            );
        }
        return HistoricalFetchResult.ok(merged);
    }

    private HistoricalFetchResult fetchWithRetry(
            BrokerHistoricalDataAdapter adapter,
            String symbol,
            String timeframe,
            Instant from,
            Instant to
    ) {
        String lastCode = "FAILED";
        String lastDetail = "unknown";
        for (int attempt = 1; attempt <= MAX_FETCH_RETRIES; attempt++) {
            HistoricalFetchResult result = adapter.fetch(new HistoricalFetchRequest(symbol, timeframe, from, to));
            if (result.success()) {
                return result;
            }
            lastCode = result.code();
            lastDetail = result.detail();
            if (!isRetryableCode(lastCode) || attempt >= MAX_FETCH_RETRIES) {
                return HistoricalFetchResult.fail(lastCode, lastDetail);
            }
            long sleepMs = (long) (Math.pow(2, attempt) * 700L) + ThreadLocalRandom.current().nextLong(200L, 800L);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return HistoricalFetchResult.fail("INTERRUPTED", "Retry interrupted");
            }
        }
        return HistoricalFetchResult.fail(lastCode, lastDetail);
    }

    private static boolean isRetryableCode(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.contains("RATE_LIMIT") || c.contains("TIMEOUT") || c.contains("5XX") || c.contains("TEMP") || c.contains("BROKER_FETCH_FAILED");
    }

    private static BigDecimal throughput(long candles, Instant started, Instant ended) {
        long secs = Math.max(1L, Duration.between(started, ended).getSeconds());
        return BigDecimal.valueOf(candles).divide(BigDecimal.valueOf(secs), 6, RoundingMode.HALF_UP);
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private static BigDecimal scale(BigDecimal v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return v.setScale(8, RoundingMode.HALF_UP);
    }

    private static Map<String, Integer> headerIndex(String[] headers) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < headers.length; i++) {
            out.put(headers[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return out;
    }

    private static Integer firstIndex(Map<String, Integer> idx, String... names) {
        for (String n : names) {
            Integer i = idx.get(n.toLowerCase(Locale.ROOT));
            if (i != null) {
                return i;
            }
        }
        return null;
    }

    private static String readCol(String[] cols, Integer idx) {
        if (idx == null || idx < 0 || idx >= cols.length) {
            return null;
        }
        return cols[idx] == null ? null : cols[idx].trim();
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Instant parseFlexibleInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            // Accept local timestamp as IST wall clock.
            return LocalDateTime.parse(v.replace(" ", "T")).atZone(IST).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static long timeframeMillis(String timeframe) {
        return switch (timeframe) {
            case "1m" -> 60_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "1h" -> 3_600_000L;
            case "1d" -> 86_400_000L;
            default -> 60_000L;
        };
    }

    private static Instant bucketStart(Instant t, String tf) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        return switch (tf) {
            case "5m" -> z.withMinute((z.getMinute() / 5) * 5).withSecond(0).withNano(0).toInstant();
            case "15m" -> z.withMinute((z.getMinute() / 15) * 15).withSecond(0).withNano(0).toInstant();
            case "1h" -> z.withMinute(0).withSecond(0).withNano(0).toInstant();
            case "1d" -> z.withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant();
            default -> z.withSecond(0).withNano(0).toInstant();
        };
    }

    private boolean hasTradingSessionOverlap(Instant from, Instant to) {
        return !tradingWindows(from, to).isEmpty();
    }

    private List<TimeWindow> tradingWindows(Instant from, Instant to) {
        List<TimeWindow> out = new ArrayList<>();
        if (from == null || to == null || !from.isBefore(to)) {
            return out;
        }
        ZonedDateTime s = from.atZone(IST);
        ZonedDateTime e = to.atZone(IST);
        ZonedDateTime day = s.toLocalDate().atStartOfDay(IST);
        ZonedDateTime endDay = e.toLocalDate().atStartOfDay(IST);
        while (!day.isAfter(endDay)) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                Instant sessionStart = day.with(NSE_OPEN).toInstant();
                Instant sessionEnd = day.with(NSE_CLOSE).toInstant();
                Instant winStart = sessionStart.isAfter(from) ? sessionStart : from;
                Instant winEnd = sessionEnd.isBefore(to) ? sessionEnd : to;
                if (winStart.isBefore(winEnd)) {
                    out.add(new TimeWindow(winStart, winEnd));
                }
            }
            day = day.plusDays(1);
        }
        return out;
    }

    private record TimeWindow(Instant start, Instant end) {}
}
