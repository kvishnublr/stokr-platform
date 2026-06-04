package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.bootstrap.config.PlatformZerodhaFeedProperties;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.MarketDataIntegrityService;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.ZerodhaKiteApiClient;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills missing intraday 1m bars via Zerodha REST when live websocket leaves gaps
 * (e.g. after reconnects). Unblocks NIFTY opening-session integrity and catalog scans.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntradaySessionGapFillService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String TIMEFRAME = "1m";
    private static final String VENDOR = "ZERODHA";
    private static final String NIFTY_50 = MarketDataIntegrityService.NIFTY_50_SYMBOL;
    private static final int NIFTY_50_TOKEN = 256265;
    private static final long RATE_MS = 350;

    private final MarketDataIntegrityService integrityService;
    private final MarketdataCandleRepository candleRepository;
    private final InstrumentRegistryService instrumentRegistry;
    private final StrategyUniverseSymbolRepository strategyUniverseSymbolRepository;
    private final PlatformZerodhaFeedProperties feedProperties;
    private final ZerodhaKiteApiClient kiteApiClient;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final FieldCipher fieldCipher;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final TransactionTemplate txTemplate;

    @Value("${stokr.intraday-gap-fill.enabled:true}")
    private boolean enabled;

    @Value("${stokr.intraday-gap-fill.min-interval-seconds:180}")
    private long minIntervalSeconds;

    @Value("${stokr.intraday-gap-fill.universe.enabled:true}")
    private boolean universeEnabled;

    @Value("${stokr.intraday-gap-fill.universe.max-symbols-per-run:30}")
    private int universeMaxSymbolsPerRun;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastNiftyAttemptAt;
    private volatile Instant lastUniverseAttemptAt;

    @Scheduled(initialDelayString = "${stokr.intraday-gap-fill.initial-delay-ms:120000}", fixedDelayString = "${stokr.intraday-gap-fill.poll-ms:120000}")
    public void scheduledGapFill() {
        if (!enabled || !isMarketHours(Instant.now())) {
            return;
        }
        fillNiftySessionGapsIfNeeded("scheduled");
        fillUniverseSessionGapsIfNeeded("scheduled");
    }

    public void fillNiftySessionGapsIfNeeded(String trigger) {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        if (!isMarketHours(now)) {
            return;
        }
        if (!integrityService.isNiftyOpeningSessionReady(now) || isNiftyIndexCandleStale(now)) {
            attemptFill(trigger, now, NIFTY_50, resolveNiftyToken(), true);
        }
    }

    public void fillUniverseSessionGapsIfNeeded(String trigger) {
        if (!enabled || !universeEnabled) {
            return;
        }
        Instant now = Instant.now();
        if (!isMarketHours(now)) {
            return;
        }
        if (lastUniverseAttemptAt != null
                && Duration.between(lastUniverseAttemptAt, now).getSeconds() < minIntervalSeconds) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            String accessToken = resolveAccessToken();
            if (accessToken == null) {
                log.warn("intraday_gap_fill.universe_skip reason=no_access_token trigger={}", trigger);
                return;
            }

            List<GapFillTarget> targets = selectUniverseTargets(now);
            if (targets.isEmpty()) {
                return;
            }

            lastUniverseAttemptAt = now;
            int filled = 0;
            int failed = 0;
            for (GapFillTarget target : targets) {
                try {
                    if (fillSymbolSession(accessToken, target.symbol(), target.token(), now)) {
                        filled++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("intraday_gap_fill.universe_failed symbol={} {}", target.symbol(), ex.toString());
                }
                Thread.sleep(RATE_MS);
            }
            log.info("intraday_gap_fill.universe_done trigger={} candidates={} filled={} failed={}",
                    trigger, targets.size(), filled, failed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
        }
    }

    private List<GapFillTarget> selectUniverseTargets(Instant now) {
        List<String> groupKeys = feedProperties.parsedSubscriptionUniverseGroupKeys();
        if (groupKeys.isEmpty()) {
            return List.of();
        }

        List<StrategyUniverseSymbol> rows =
                strategyUniverseSymbolRepository.findAllEnabledByGroupKeys(groupKeys);
        Map<String, GapFillTarget> targets = new LinkedHashMap<>();
        for (StrategyUniverseSymbol row : rows) {
            if (!isGapFillCandidate(row)) {
                continue;
            }
            String symbol = canonicalSymbol(row);
            if (symbol.isBlank() || NIFTY_50.equalsIgnoreCase(symbol)) {
                continue;
            }
            Integer token = resolveToken(row, symbol);
            if (token == null || token <= 0) {
                continue;
            }
            targets.putIfAbsent(symbol, new GapFillTarget(symbol, token));
        }

        return targets.values().stream()
                .filter(target -> needsSessionGapFill(target.symbol(), now))
                .sorted(Comparator.comparingInt((GapFillTarget t) -> gapPriority(t.symbol(), now)).reversed())
                .limit(Math.max(1, universeMaxSymbolsPerRun))
                .toList();
    }

    private static boolean isGapFillCandidate(StrategyUniverseSymbol row) {
        if (row == null || !row.isEnabled()) {
            return false;
        }
        String exchange = normalize(row.getExchange());
        String instrumentType = normalize(row.getInstrumentType());
        if ("CDS".equals(exchange) && ("CUR".equals(instrumentType) || instrumentType.isBlank())) {
            return true;
        }
        return "NSE".equals(exchange) && ("EQ".equals(instrumentType) || instrumentType.isBlank());
    }

    private static String canonicalSymbol(StrategyUniverseSymbol row) {
        if (row.getSymbol() != null && !row.getSymbol().isBlank()) {
            return row.getSymbol().trim();
        }
        return row.getTradingSymbol() != null ? row.getTradingSymbol().trim() : "";
    }

    private Integer resolveToken(StrategyUniverseSymbol row, String symbol) {
        if (row.getInstrumentToken() != null && row.getInstrumentToken() > 0) {
            return row.getInstrumentToken().intValue();
        }
        Integer token = instrumentRegistry.getToken(symbol);
        if (token != null) {
            return token;
        }
        if (row.getTradingSymbol() != null && !row.getTradingSymbol().isBlank()) {
            return instrumentRegistry.getToken(row.getTradingSymbol().trim());
        }
        return null;
    }

    private int gapPriority(String symbol, Instant now) {
        LocalDate sessionDate = now.atZone(IST).toLocalDate();
        Instant sessionStart = CdsMarketSession.sessionStart(sessionDate, symbol);
        List<MarketdataCandle> bars = candleRepository
                .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        symbol, TIMEFRAME, sessionStart, now);
        if (bars.isEmpty()) {
            return 1000;
        }
        long minutesOpen = Duration.between(sessionStart, now).toMinutes();
        long expectedBars = Math.max(10, minutesOpen - 2);
        return (int) Math.max(0, expectedBars - bars.size());
    }

    private boolean needsSessionGapFill(String symbol, Instant now) {
        LocalDate sessionDate = now.atZone(IST).toLocalDate();
        Instant sessionStart = CdsMarketSession.sessionStart(sessionDate, symbol);
        List<MarketdataCandle> bars = candleRepository
                .findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
                        symbol, TIMEFRAME, sessionStart, now);
        if (bars.isEmpty()) {
            return true;
        }

        Instant latest = bars.get(bars.size() - 1).getOpenTime();
        if (latest != null && Duration.between(latest, now).getSeconds() > 180) {
            return true;
        }

        List<MarketdataCandle> recentTail = contiguousTail(bars, Duration.ofMinutes(3), 31);
        if (recentTail.size() >= 31 && latest != null && Duration.between(latest, now).getSeconds() <= 180) {
            return false;
        }

        long minutesOpen = Duration.between(sessionStart, now).toMinutes();
        long expectedBars = Math.max(10, minutesOpen - 2);
        if (bars.size() < (expectedBars * 3 / 4)) {
            return true;
        }

        for (int i = 1; i < bars.size(); i++) {
            Duration gap = Duration.between(bars.get(i - 1).getOpenTime(), bars.get(i).getOpenTime());
            if (gap.toMinutes() > 3) {
                return true;
            }
        }
        return false;
    }

    private static List<MarketdataCandle> contiguousTail(
            List<MarketdataCandle> bars, Duration maxGap, int minBars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        int end = bars.size() - 1;
        int start = end;
        while (start > 0) {
            Duration gap = Duration.between(bars.get(start - 1).getOpenTime(), bars.get(start).getOpenTime());
            if (gap.compareTo(maxGap) > 0) {
                break;
            }
            start--;
        }
        if (end - start + 1 < minBars) {
            return List.of();
        }
        return bars.subList(start, end + 1);
    }

    private boolean isNiftyIndexCandleStale(Instant now) {
        return candleRepository.findTopBySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(NIFTY_50, TIMEFRAME)
                .map(MarketdataCandle::getOpenTime)
                .map(openTime -> Duration.between(openTime, now).getSeconds() > 180)
                .orElse(true);
    }

    private void attemptFill(String trigger, Instant now, String symbol, int token, boolean niftyAttempt) {
        if (niftyAttempt) {
            if (lastNiftyAttemptAt != null
                    && Duration.between(lastNiftyAttemptAt, now).getSeconds() < minIntervalSeconds) {
                return;
            }
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            String accessToken = resolveAccessToken();
            if (accessToken == null) {
                log.warn("intraday_gap_fill.skip reason=no_access_token trigger={} symbol={}", trigger, symbol);
                return;
            }
            if (fillSymbolSession(accessToken, symbol, token, now)) {
                if (niftyAttempt) {
                    lastNiftyAttemptAt = now;
                }
                log.info("intraday_gap_fill.done trigger={} symbol={} sessionReady={}",
                        trigger, symbol, integrityService.isNiftyOpeningSessionReady(now));
            }
        } catch (Exception ex) {
            log.warn("intraday_gap_fill.failed trigger={} symbol={} {}", trigger, symbol, ex.toString());
        } finally {
            running.set(false);
        }
    }

    private boolean fillSymbolSession(String accessToken, String symbol, int token, Instant now)
            throws InterruptedException {
        Instant from = CdsMarketSession.sessionStart(now.atZone(IST).toLocalDate(), symbol);
        Instant to = now.atZone(IST).withSecond(0).withNano(0).minusMinutes(1).toInstant();

        List<ParsedCandle> parsed = fetchSessionCandles(accessToken, symbol, token, from, to);
        if (parsed.isEmpty()) {
            log.warn("intraday_gap_fill.empty trigger=live symbol={} token={} from={} to={}",
                    symbol, token, from, to);
            return false;
        }

        upsertCandles(symbol, parsed);
        log.info("intraday_gap_fill.symbol_done symbol={} token={} fetched={} upserted={}",
                symbol, token, parsed.size(), parsed.size());
        return true;
    }

    private List<ParsedCandle> fetchSessionCandles(
            String accessToken, String symbol, int token, Instant from, Instant to)
            throws InterruptedException {
        List<ParsedCandle> parsed = new ArrayList<>();
        Instant chunkStart = from;
        while (chunkStart.isBefore(to)) {
            Instant chunkEnd = chunkStart.plus(1, ChronoUnit.HOURS);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            JsonNode response = kiteApiClient.getHistoricalCandles(
                    zerodhaBrokerProperties.getApiKey(),
                    accessToken,
                    token,
                    "minute",
                    chunkStart,
                    chunkEnd);
            JsonNode candlesNode = response.path("data").path("candles");
            if (candlesNode.isArray() && !candlesNode.isEmpty()) {
                parsed.addAll(parseRows(candlesNode));
            }
            chunkStart = chunkEnd.plus(1, ChronoUnit.MINUTES);
            if (chunkStart.isBefore(to)) {
                Thread.sleep(RATE_MS);
            }
        }
        return parsed;
    }

    private void upsertCandles(String symbol, List<ParsedCandle> candles) {
        txTemplate.executeWithoutResult(status -> {
            for (ParsedCandle c : candles) {
                candleRepository.upsertCandle(
                        symbol, TIMEFRAME, c.openTime(),
                        c.open(), c.high(), c.low(), c.close(), c.volume());
            }
        });
    }

    private int resolveNiftyToken() {
        return instrumentRegistry.getSymbolToToken().entrySet().stream()
                .filter(e -> NIFTY_50.equalsIgnoreCase(e.getKey()))
                .mapToInt(Map.Entry::getValue)
                .findFirst()
                .orElse(NIFTY_50_TOKEN);
    }

    private String resolveAccessToken() {
        try {
            platformMarketFeedService.ensureSessionFromTraderFallback(VENDOR);
            platformMarketFeedService.ensureValidPlatformZerodhaToken(Duration.ofHours(2));
            PlatformBrokerFeedSession session = sessionRepository
                    .findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).orElse(null);
            if (session == null || session.getAccessTokenEnc() == null) {
                return null;
            }
            String token = fieldCipher.decrypt(session.getAccessTokenEnc());
            return (token == null || token.isBlank()) ? null : token;
        } catch (Exception ex) {
            log.warn("intraday_gap_fill.token_resolve_failed {}", ex.getMessage());
            return null;
        }
    }

    private static List<ParsedCandle> parseRows(JsonNode candlesNode) {
        List<ParsedCandle> out = new ArrayList<>();
        for (JsonNode row : candlesNode) {
            if (!row.isArray() || row.size() < 6) {
                continue;
            }
            try {
                Instant openTime = Instant.parse(normalizeKiteDateTime(row.get(0).asText()));
                BigDecimal open = new BigDecimal(row.get(1).asText());
                BigDecimal high = new BigDecimal(row.get(2).asText());
                BigDecimal low = new BigDecimal(row.get(3).asText());
                BigDecimal close = new BigDecimal(row.get(4).asText());
                BigDecimal volume = new BigDecimal(row.get(5).asText());
                out.add(new ParsedCandle(openTime, open, high, low, close, volume));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String normalizeKiteDateTime(String kiteTs) {
        if (kiteTs == null) {
            return null;
        }
        return kiteTs.replaceAll("\\+0530$", "+05:30");
    }

    private static Instant sessionStart(LocalDate sessionDate) {
        return CdsMarketSession.sessionStart(sessionDate, null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isMarketHours(Instant now) {
        ZonedDateTime zdt = now.atZone(IST);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        boolean nse = !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
        return nse || CdsMarketSession.isCdsMarketHours(now);
    }

    private record GapFillTarget(String symbol, int token) {
    }

    private record ParsedCandle(
            Instant openTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume) {
    }
}
