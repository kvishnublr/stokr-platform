package com.stokr.marketdata.tick;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.marketdata.Universe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.stokr.broker.BrokerAccount;
import java.util.*;

/**
 * Option B: Faster REST polling for the active strategy universe (20-60 symbols).
 * Runs every 15 seconds during market hours to provide sub-minute visibility.
 * Complements the WebSocket (which covers NIFTY_500 full) by ensuring priority
 * symbols get faster updates even if the WebSocket disconnects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActiveSymbolPoller {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String KITE_API_BASE = "https://api.kite.trade";

    @Value("${broker.zerodha.api-key:}")
    private String zerodhaApiKey;

    private final BrokerAccountRepository brokerAccountRepo;
    private final Universe universe;
    private final TickAggregatorService aggregator;
    private final KiteTickWebSocketClient wsClient;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // Last-known volume per symbol (for minute-volume delta)
    private final Map<String, Long> lastVolMap = new HashMap<>();
    // Last-known LTP per symbol
    private final Map<String, BigDecimal> lastLtp = new HashMap<>();

    /**
     * Runs every 15 seconds from 9:15 to 15:30, Mon-Fri.
     * Only fetches for the active strategy universe (~20-60 symbols).
     */
    @Scheduled(cron = "0/15 15-30 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void fastPoll() {
        if (wsClient.isConnected()) return; // WebSocket is covering data — skip to save quota

        String token = resolveToken();
        if (token == null) return;

        List<String> symbols = universe.getSymbols();
        if (symbols.isEmpty()) return;

        List<List<String>> batches = partition(symbols, 50); // Kite allows up to 500 per request, use 50
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "token " + zerodhaApiKey + ":" + token);
        headers.set("X-Kite-Version", "3");

        LocalDateTime now = LocalDateTime.now(IST);

        for (List<String> batch : batches) {
            try {
                StringBuilder url = new StringBuilder(KITE_API_BASE + "/quote?");
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) url.append("&");
                    url.append("i=NSE:").append(batch.get(i));
                }

                ResponseEntity<String> resp = restTemplate.exchange(
                    url.toString(), HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode root = mapper.readTree(resp.getBody());
                if (!"success".equals(root.path("status").asText())) continue;

                JsonNode data = root.path("data");
                for (String symbol : batch) {
                    JsonNode q = data.path("NSE:" + symbol);
                    if (q.isMissingNode()) continue;

                    BigDecimal ltp = bd(q, "last_price");
                    long totalVol = q.path("volume").asLong(0);
                    long prevVol = lastVolMap.getOrDefault(symbol, 0L);
                    long deltaVol = Math.max(0, totalVol - prevVol);
                    lastVolMap.put(symbol, totalVol);

                    // Build a synthetic TickData from the REST response
                    var tick = TickData.builder()
                        .symbol(symbol)
                        .exchangeTs(now)
                        .receivedTs(now)
                        .ltp(ltp)
                        .volume(totalVol)
                        .minuteVolume(deltaVol)
                        .buyQuantity(0)
                        .sellQuantity(0)
                        .changePct(lastLtp.containsKey(symbol) && lastLtp.get(symbol).compareTo(BigDecimal.ZERO) > 0
                            ? ltp.subtract(lastLtp.get(symbol)).divide(lastLtp.get(symbol), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO)
                        .createdAt(java.time.Instant.now())
                        .build();

                    lastLtp.put(symbol, ltp);
                    aggregator.onTick(tick);
                }
            } catch (Exception e) {
                log.warn("Fast poll batch failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Cleanup old tick data daily at 20:00.
     */
    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "Asia/Kolkata")
    public void cleanupOldTicks() {
        // The DB function cleanup_tick_tables() handles this via cron or manual call
        log.info("Tick cleanup scheduled (handled by DB cleanup_tick_tables function)");
    }

    private String resolveToken() {
        if (zerodhaApiKey == null || zerodhaApiKey.isBlank()) return null;
        return brokerAccountRepo.findByBrokerNameAndStatus("ZERODHA", "ACTIVE")
            .stream().filter(a -> a.getAccessToken() != null)
            .findFirst().map(BrokerAccount::getAccessToken)
            .orElse(null);
    }

    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        return new BigDecimal(n.asText());
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            parts.add(list.subList(i, Math.min(i + size, list.size())));
        return parts;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(8000);
        return new RestTemplate(f);
    }
}
