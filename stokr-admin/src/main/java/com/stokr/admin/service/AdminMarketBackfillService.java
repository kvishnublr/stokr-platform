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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
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

    private void validateBrokerPreflight(String brokerSource, String timeframe, Instant rangeStart, Instant rangeEnd, List<String> symbols) {
        BrokerHistoricalDataAdapter adapter = historicalAdapterRegistry.require(brokerSource.trim().toUpperCase(Locale.ROOT));
        Instant probeEnd = rangeStart.plus(Duration.ofMinutes(5));
        if (!probeEnd.isBefore(rangeEnd)) {
            probeEnd = rangeEnd;
        }
        int sample = Math.max(1, Math.min(preflightSampleSize, symbols.size()));
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < sample; i++) {
            String symbol = symbols.get(i);
            HistoricalFetchResult probe = adapter.fetch(new HistoricalFetchRequest(symbol, timeframe, rangeStart, probeEnd));
            if (!probe.success()) {
                failed.add(symbol + ":" + probe.code());
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
            if (!fetch.success() && fetch.candles().isEmpty()) {
                failures++;
                row.setStatus(MarketBackfillSymbolStatus.FAILED);
                row.setFailureCount(row.getFailureCount() + 1);
                row.setMessage(fetch.code() + ": " + fetch.detail());
                jobSymbolRepository.save(row);
                saveFailure(jobId, row.getSymbol(), fetch.code(), fetch.detail(), isRetryableCode(fetch.code()));
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
                row.setMessage(fetch.code() + ": " + fetch.detail() + " (partial candles persisted)");
                saveFailure(jobId, row.getSymbol(), fetch.code(), fetch.detail(), isRetryableCode(fetch.code()));
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
                row.setMessage("Fetched");
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
    }

    private List<String> resolveSymbols(String group, List<String> custom) {
        String g = group == null ? "" : group.trim().toUpperCase(Locale.ROOT);
        if ("CUSTOM".equals(g)) {
            if (custom == null) {
                return List.of();
            }
            return custom.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
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
        return fallbackNifty50();
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
        Instant cursor = rangeStart;
        List<HistoricalCandlePoint> merged = new ArrayList<>();
        boolean hadChunkIssues = false;
        String lastChunkIssue = null;
        while (cursor.isBefore(rangeEnd)) {
            Instant chunkEnd = cursor.plus(Duration.ofMinutes(Math.max(60, zerodhaChunkMinutes)));
            if (chunkEnd.isAfter(rangeEnd)) {
                chunkEnd = rangeEnd;
            }
            HistoricalFetchResult chunk = fetchWithRetry(useAdapter, symbol, timeframe, cursor, chunkEnd);
            if (!chunk.success()) {
                if (merged.isEmpty()) {
                    return chunk;
                }
                // Preserve already-fetched candles and continue with the next chunk boundary.
                hadChunkIssues = true;
                lastChunkIssue = chunk.code() + ":" + chunk.detail();
                cursor = chunkEnd;
                continue;
            }
            if (chunk.candles().isEmpty()) {
                // Empty chunk can occur in non-trading intervals; move forward instead of failing symbol.
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
        if (merged.isEmpty()) {
            return HistoricalFetchResult.fail("NO_CANDLES_RETURNED", "No candles returned");
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
}


