package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.marketdata.integrity.MarketDataIntegrityService;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
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

    private final MarketDataIntegrityService integrityService;
    private final MarketdataCandleRepository candleRepository;
    private final InstrumentRegistryService instrumentRegistry;
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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastAttemptAt;

    @Scheduled(initialDelayString = "${stokr.intraday-gap-fill.initial-delay-ms:120000}", fixedDelayString = "${stokr.intraday-gap-fill.poll-ms:120000}")
    public void scheduledGapFill() {
        if (!enabled || !isMarketHours(Instant.now())) {
            return;
        }
        fillNiftySessionGapsIfNeeded("scheduled");
    }

    public void fillNiftySessionGapsIfNeeded(String trigger) {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        if (!isMarketHours(now)) {
            return;
        }
        if (!integrityService.isNiftyOpeningSessionReady(now)) {
            attemptFill(trigger, now);
        }
    }

    private void attemptFill(String trigger, Instant now) {
        if (lastAttemptAt != null
                && Duration.between(lastAttemptAt, now).getSeconds() < minIntervalSeconds) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            String accessToken = resolveAccessToken();
            if (accessToken == null) {
                log.warn("intraday_gap_fill.skip reason=no_access_token trigger={}", trigger);
                return;
            }
            int token = resolveNiftyToken();
            ZonedDateTime sessionStart = now.atZone(IST).toLocalDate().atTime(9, 15).atZone(IST);
            Instant from = sessionStart.toInstant();
            // Zerodha may return an empty array when `to` includes the in-progress minute.
            Instant to = now.atZone(IST).withSecond(0).withNano(0).minusMinutes(1).toInstant();

            JsonNode response = kiteApiClient.getHistoricalCandles(
                    zerodhaBrokerProperties.getApiKey(),
                    accessToken,
                    token,
                    "minute",
                    from,
                    to);
            JsonNode candlesNode = response.path("data").path("candles");
            if (!candlesNode.isArray() || candlesNode.isEmpty()) {
                String status = response.path("status").asText("unknown");
                String message = response.path("message").asText("");
                log.warn("intraday_gap_fill.empty trigger={} symbol={} token={} status={} message={} from={} to={} body={}",
                        trigger, NIFTY_50, token, status, message, from, to,
                        response.toString().length() > 400 ? response.toString().substring(0, 400) + "..." : response.toString());
                return;
            }

            lastAttemptAt = now;
            List<ParsedCandle> parsed = parseRows(candlesNode);
            int upserted = upsertCandles(parsed);
            log.info("intraday_gap_fill.done trigger={} symbol={} fetched={} upserted={} sessionReady={}",
                    trigger, NIFTY_50, parsed.size(), upserted,
                    integrityService.isNiftyOpeningSessionReady(now));
        } catch (Exception ex) {
            log.warn("intraday_gap_fill.failed trigger={} {}", trigger, ex.toString());
        } finally {
            running.set(false);
        }
    }

    private int upsertCandles(List<ParsedCandle> candles) {
        txTemplate.executeWithoutResult(status -> {
            for (ParsedCandle c : candles) {
                candleRepository.upsertCandle(
                        NIFTY_50, TIMEFRAME, c.openTime(),
                        c.open(), c.high(), c.low(), c.close(), c.volume());
            }
        });
        return candles.size();
    }

    private int resolveNiftyToken() {
        return instrumentRegistry.getSymbolToToken().entrySet().stream()
                .filter(e -> NIFTY_50.equalsIgnoreCase(e.getKey()))
                .mapToInt(e -> e.getValue())
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

    private static boolean isMarketHours(Instant now) {
        ZonedDateTime zdt = now.atZone(IST);
        if (zdt.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = zdt.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
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
