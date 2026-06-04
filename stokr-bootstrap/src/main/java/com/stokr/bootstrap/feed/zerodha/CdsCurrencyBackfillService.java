package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.user.broker.BrokerExecutionCredentialService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backfills 1m candles for CDS major pairs (USDINR, EURINR) so currency strategies can evaluate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CdsCurrencyBackfillService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String TIMEFRAME = "1m";
    private static final String VENDOR = "ZERODHA";
    private static final long RATE_MS = 350;

    private final ZerodhaKiteApiClient kiteApiClient;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final FieldCipher fieldCipher;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final BrokerExecutionCredentialService brokerExecutionCredentialService;
    private final MarketdataCandleRepository candleRepository;
    private final TransactionTemplate txTemplate;

    @Value("${stokr.cds-backfill.enabled:true}")
    private boolean enabled;

    @Value("${stokr.cds-backfill.lookback-days:10}")
    private int lookbackDays;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(initialDelayString = "90000", fixedDelayString = "86400000")
    public void backfillOnStartup() {
        runBackfill("startup");
    }

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void backfillPreMarket() {
        runBackfill("pre-market");
    }

    private void runBackfill(String trigger) {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            doBackfill(trigger);
        } finally {
            running.set(false);
        }
    }

    private void doBackfill(String trigger) {
        try {
            platformMarketFeedService.refreshFromKite(VENDOR);
        } catch (Exception ex) {
            log.warn("cds_backfill.token_refresh_skipped trigger={} {}", trigger, ex.getMessage());
        }
        String accessToken = resolveAccessToken();
        if (accessToken == null) {
            log.warn("cds_backfill.skip reason=no_access_token trigger={}", trigger);
            return;
        }
        String apiKey = zerodhaBrokerProperties.getApiKey();
        Map<String, Integer> pairs;
        try {
            String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "CDS");
            pairs = CdsInstrumentResolver.resolveMajorPairs(csv);
        } catch (Exception ex) {
            log.warn("cds_backfill.skip reason=instrument_resolve_failed trigger={} {}", trigger, ex.toString());
            return;
        }
        if (pairs.isEmpty()) {
            log.warn("cds_backfill.skip reason=no_pairs_resolved trigger={}", trigger);
            return;
        }

        LocalDate today = LocalDate.now(IST);
        Instant to = today.atTime(17, 0).atZone(IST).toInstant();
        Instant from = today.minusDays(lookbackDays).atTime(9, 0).atZone(IST).toInstant();

        int filled = 0;
        for (Map.Entry<String, Integer> entry : pairs.entrySet()) {
            String symbol = entry.getKey();
            int token = entry.getValue();
            try {
                int upserted = fetchAndUpsert(apiKey, accessToken, symbol, token, from, to);
                if (upserted > 0) {
                    filled++;
                    log.info("cds_backfill.filled trigger={} symbol={} candles={}", trigger, symbol, upserted);
                }
                Thread.sleep(RATE_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("cds_backfill.failed trigger={} symbol={} {}", trigger, symbol, ex.toString());
            }
        }
        log.info("cds_backfill.done trigger={} pairs={} filled={}", trigger, pairs.size(), filled);
    }

    private int fetchAndUpsert(
            String apiKey, String accessToken, String symbol, int token, Instant from, Instant to)
            throws InterruptedException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return fetchAndUpsertOnce(apiKey, accessToken, symbol, token, from, to);
            } catch (org.springframework.web.client.HttpClientErrorException.Forbidden ex) {
                if (attempt >= 2) {
                    throw ex;
                }
                platformMarketFeedService.ensureValidPlatformZerodhaToken(java.time.Duration.ofMinutes(30));
                String refreshed = resolveAccessToken();
                if (refreshed == null || refreshed.isBlank()) {
                    refreshed = resolvePrimaryTraderAccessToken();
                }
                if (refreshed == null || refreshed.isBlank() || refreshed.equals(accessToken)) {
                    throw ex;
                }
                accessToken = refreshed;
                log.warn("cds_backfill.retry symbol={} reason=token_forbidden", symbol);
            }
        }
        return 0;
    }

    private int fetchAndUpsertOnce(
            String apiKey, String accessToken, String symbol, int token, Instant from, Instant to)
            throws InterruptedException {
        List<ParsedCandle> parsed = new ArrayList<>();
        Instant chunkStart = from;
        while (chunkStart.isBefore(to)) {
            Instant chunkEnd = chunkStart.plus(1, ChronoUnit.HOURS);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            JsonNode response = kiteApiClient.getHistoricalCandles(
                    apiKey, accessToken, token, "minute", chunkStart, chunkEnd);
            JsonNode candlesNode = response.path("data").path("candles");
            if (candlesNode.isArray() && !candlesNode.isEmpty()) {
                parsed.addAll(parseRows(candlesNode));
            }
            chunkStart = chunkEnd.plus(1, ChronoUnit.MINUTES);
            if (chunkStart.isBefore(to)) {
                Thread.sleep(RATE_MS);
            }
        }
        if (parsed.isEmpty()) {
            return 0;
        }
        txTemplate.executeWithoutResult(status -> {
            for (ParsedCandle c : parsed) {
                candleRepository.upsertCandle(
                        symbol, TIMEFRAME, c.openTime(),
                        c.open(), c.high(), c.low(), c.close(), c.volume());
            }
        });
        return parsed.size();
    }

    private static List<ParsedCandle> parseRows(JsonNode candlesNode) {
        List<ParsedCandle> out = new ArrayList<>();
        for (JsonNode row : candlesNode) {
            if (!row.isArray() || row.size() < 6) {
                continue;
            }
            try {
                Instant openTime = Instant.parse(normalizeKiteDateTime(row.get(0).asText()));
                out.add(new ParsedCandle(
                        openTime,
                        new BigDecimal(row.get(1).asText()),
                        new BigDecimal(row.get(2).asText()),
                        new BigDecimal(row.get(3).asText()),
                        new BigDecimal(row.get(4).asText()),
                        new BigDecimal(row.get(5).asText())));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String normalizeKiteDateTime(String kiteTs) {
        return kiteTs == null ? null : kiteTs.replaceAll("\\+0530$", "+05:30");
    }

    private String resolveAccessToken() {
        try {
            platformMarketFeedService.ensureSessionFromTraderFallback(VENDOR);
            platformMarketFeedService.ensureValidPlatformZerodhaToken(java.time.Duration.ofHours(2));
            PlatformBrokerFeedSession session = sessionRepository
                    .findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).orElse(null);
            if (session == null || session.getAccessTokenEnc() == null) {
                return resolvePrimaryTraderAccessToken();
            }
            String token = fieldCipher.decrypt(session.getAccessTokenEnc());
            if (token == null || token.isBlank()) {
                return resolvePrimaryTraderAccessToken();
            }
            return token;
        } catch (Exception ex) {
            log.warn("cds_backfill.token_resolve_failed {}", ex.getMessage());
            return resolvePrimaryTraderAccessToken();
        }
    }

    private String resolvePrimaryTraderAccessToken() {
        return brokerExecutionCredentialService.primaryTraderUserId()
                .flatMap(userId -> brokerExecutionCredentialService.resolve(userId, VENDOR))
                .map(BrokerExecutionCredentialService.ResolvedCredentials::accessToken)
                .filter(t -> t != null && !t.isBlank())
                .orElse(null);
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
