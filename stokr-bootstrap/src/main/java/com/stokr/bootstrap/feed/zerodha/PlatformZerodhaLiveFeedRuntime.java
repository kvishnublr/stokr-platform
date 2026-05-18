package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.bootstrap.config.PlatformZerodhaFeedProperties;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.ZerodhaKiteApiClient;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process Zerodha Kite WebSocket for the admin platform feed. Updates {@code platform_broker_feed_sessions} with real
 * packet/tick counters and publishes {@link PlatformLiveTickEvent} for ingestion into {@code marketdata_ticks} / candles.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformZerodhaLiveFeedRuntime {

    private static final String VENDOR = "ZERODHA";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final PlatformZerodhaFeedProperties feedProperties;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;
    private final ZerodhaKiteApiClient kiteApiClient;
    private final FieldCipher fieldCipher;
    private final PlatformBrokerFeedSessionRepository sessionRepository;
    private final PlatformMarketFeedService platformMarketFeedService;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformZerodhaFeedTelemetryService telemetryService;
    private final ObjectMapper objectMapper;

    private final AtomicReference<WebSocket> activeSocket = new AtomicReference<>();
    private final AtomicBoolean wsOpen = new AtomicBoolean(false);
    private final AtomicBoolean closedSinceLastOpen = new AtomicBoolean(false);
    private final AtomicBoolean handshakePending = new AtomicBoolean(false);
    private final Object ensureGate = new Object();
    private final ByteArrayOutputStream binaryAcc = new ByteArrayOutputStream();
    private final AtomicLong windowPackets = new AtomicLong();
    private final AtomicLong windowTicks = new AtomicLong();
    private final AtomicLong windowStartNanos = new AtomicLong(System.nanoTime());
    private volatile Instant lastPacketAt;
    private volatile Instant lastTickAt;
    private volatile Instant lastHeartbeatAt;
    private volatile Map<Integer, String> tokenSymbols = Map.of();
    private volatile List<Integer> subscribedTokens = List.of();

    private ScheduledExecutorService scheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "platform-zerodha-feed");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeEnsure, 2, 3, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::safeFlush, 2, 2, TimeUnit.SECONDS);
    }

    private void safeEnsure() {
        try {
            ensureConnectedIfNeeded();
        } catch (Exception ex) {
            log.debug("platform.ws.ensure_failed {}", ex.toString());
        }
    }

    private void safeFlush() {
        try {
            flushWindow();
        } catch (Exception ex) {
            log.debug("platform.ws.flush_failed {}", ex.toString());
        }
    }

    private void ensureConnectedIfNeeded() {
        if (!feedProperties.isLiveFeedEnabled()) {
            closeActive("live_feed_disabled");
            return;
        }
        if (!zerodhaBrokerProperties.isConfigured()) {
            closeActive("zerodha_api_not_configured");
            return;
        }
        PlatformBrokerFeedSession session = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).orElse(null);
        if (session == null) {
            boolean bootstrapped = platformMarketFeedService.ensureSessionFromTraderFallback(VENDOR);
            if (bootstrapped) {
                session = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).orElse(null);
            }
        }
        if (session == null || session.isIngestionPaused()) {
            closeActive(session == null ? "no_platform_session" : "ingestion_paused");
            return;
        }
        if (session.getAccessTokenEnc() == null || session.getAccessTokenEnc().isBlank()) {
            closeActive("no_access_token");
            return;
        }
        if (session.getTokenExpiresAt() != null && session.getTokenExpiresAt().isBefore(Instant.now())) {
            closeActive("token_expired");
            return;
        }
        if (wsOpen.get() && activeSocket.get() != null) {
            return;
        }
        String accessToken;
        try {
            accessToken = fieldCipher.decrypt(session.getAccessTokenEnc());
        } catch (Exception ex) {
            log.warn("platform.ws.token_decrypt {}", ex.getClass().getSimpleName());
            return;
        }
        if (accessToken == null || accessToken.isBlank()) {
            closeActive("token_decrypt_empty");
            return;
        }
        String apiKey = zerodhaBrokerProperties.getApiKey();
        List<Integer> tokens = feedProperties.parsedInstrumentTokens();
        this.subscribedTokens = tokens;
        this.tokenSymbols = resolveSymbols(apiKey, accessToken, tokens);

        synchronized (ensureGate) {
            if (wsOpen.get() && activeSocket.get() != null) {
                return;
            }
            if (!handshakePending.compareAndSet(false, true)) {
                return;
            }

            telemetryService.markConnecting(VENDOR);

            String url = "wss://ws.kite.trade?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);

            WebSocket.Listener listener = new KiteListener(tokens);
            CompletableFuture<WebSocket> fut = HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(url), listener);
            fut.whenComplete((ws, err) -> {
                handshakePending.set(false);
                if (err != null) {
                    log.warn("platform.ws.connect_failed {}", err.toString());
                    telemetryService.markWebsocketClosed(VENDOR, "connect_failed: " + err.getClass().getSimpleName());
                    wsOpen.set(false);
                    return;
                }
                WebSocket prev = activeSocket.getAndSet(ws);
                if (prev != null && prev != ws) {
                    try {
                        prev.sendClose(WebSocket.NORMAL_CLOSURE, "superseded").join();
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    private Map<Integer, String> resolveSymbols(String apiKey, String accessToken, List<Integer> want) {
        Map<Integer, String> map = new HashMap<>();
        for (int id : want) {
            map.put(id, "TOKEN_" + id);
        }
        List<String> configuredSymbols = feedProperties.parsedInstrumentSymbols();
        for (int i = 0; i < Math.min(want.size(), configuredSymbols.size()); i++) {
            String configured = configuredSymbols.get(i);
            if (configured != null && !configured.isBlank()) {
                map.put(want.get(i), configured.trim());
            }
        }
        // Try NSE then NFO — futures tokens won't appear in the NSE list
        for (String exchange : List.of("NSE", "NFO")) {
            boolean anyUnresolved = map.values().stream().anyMatch(v -> v.startsWith("TOKEN_"));
            if (!anyUnresolved) {
                break;
            }
            try {
                String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, exchange);
                BufferedReader br = new BufferedReader(new StringReader(csv));
                String header = br.readLine();
                if (header == null) {
                    continue;
                }
                String[] hc = header.split(",");
                int it = -1;
                int ts = -1;
                for (int i = 0; i < hc.length; i++) {
                    String c = hc[i].trim();
                    if ("instrument_token".equalsIgnoreCase(c)) {
                        it = i;
                    }
                    if ("tradingsymbol".equalsIgnoreCase(c)) {
                        ts = i;
                    }
                }
                if (it < 0 || ts < 0) {
                    continue;
                }
                String line;
                while ((line = br.readLine()) != null) {
                    String[] p = line.split(",");
                    if (p.length <= Math.max(it, ts)) {
                        continue;
                    }
                    int tok = (int) Long.parseLong(p[it].trim());
                    if (map.containsKey(tok)) {
                        map.put(tok, p[ts].trim());
                    }
                }
            } catch (Exception ex) {
                log.debug("platform.ws.instruments_parse exchange={} {}", exchange, ex.toString());
            }
        }
        return map;
    }

    private void closeActive(String reason) {
        WebSocket ws;
        synchronized (ensureGate) {
            ws = activeSocket.getAndSet(null);
            wsOpen.set(false);
            handshakePending.set(false);
        }
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            } catch (Exception ignored) {
            }
        }
        telemetryService.markWebsocketClosed(VENDOR, reason);
    }

    private void flushWindow() {
        long elapsed = Math.max(1L, (System.nanoTime() - windowStartNanos.get()) / 1_000_000_000L);
        long pk = windowPackets.getAndSet(0);
        long tk = windowTicks.getAndSet(0);
        windowStartNanos.set(System.nanoTime());
        double pps = pk / (double) elapsed;
        double tps = tk / (double) elapsed;
        int subs = subscribedTokens.size();
        String wsState = wsOpen.get() ? "OPEN" : "CLOSED";
        String streamingSymbolsCsv = subscribedTokens.stream()
                .map(tok -> tokenSymbols.getOrDefault(tok, "TOKEN_" + tok))
                .collect(Collectors.joining(","));
        telemetryService.saveWindow(
                VENDOR,
                new PlatformZerodhaFeedTelemetryService.PlatformFeedWindowMetrics(
                        pps,
                        tps,
                        subs,
                        wsState,
                        false,
                        lastPacketAt,
                        lastTickAt,
                        lastHeartbeatAt,
                        null,
                        null,
                        streamingSymbolsCsv
                )
        );
    }

    @PreDestroy
    public void shutdown() {
        closeActive("application_shutdown");
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private final class KiteListener implements WebSocket.Listener {

        private final List<Integer> tokens;

        private KiteListener(List<Integer> tokens) {
            this.tokens = tokens;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (closedSinceLastOpen.compareAndSet(true, false)) {
                telemetryService.markReconnectBump(VENDOR);
            }
            wsOpen.set(true);
            try {
                String sub = objectMapper.writeValueAsString(Map.of("a", "subscribe", "v", tokens));
                webSocket.sendText(sub, true);
                String mode = objectMapper.writeValueAsString(Map.of("a", "mode", "v", List.of("ltp", tokens)));
                webSocket.sendText(mode, true);
            } catch (Exception ex) {
                log.warn("platform.ws.subscribe_failed {}", ex.toString());
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                String s = data.toString();
                if (s.length() == 1) {
                    lastHeartbeatAt = Instant.now();
                    lastPacketAt = lastHeartbeatAt;
                    windowPackets.incrementAndGet();
                } else {
                    JsonNode n = objectMapper.readTree(s);
                    if (n.has("type") && "error".equalsIgnoreCase(n.path("type").asText())) {
                        log.warn("platform.ws.kite_error {}", n.path("data").asText());
                    }
                    lastPacketAt = Instant.now();
                    windowPackets.incrementAndGet();
                }
            } catch (Exception ex) {
                log.debug("platform.ws.text_parse {}", ex.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer message, boolean last) {
            byte[] chunk = new byte[message.remaining()];
            message.get(chunk);
            synchronized (binaryAcc) {
                try {
                    binaryAcc.write(chunk);
                } catch (Exception ex) {
                    log.debug("platform.ws.binary_acc {}", ex.toString());
                }
                if (!last) {
                    webSocket.request(1);
                    return null;
                }
                byte[] full = binaryAcc.toByteArray();
                binaryAcc.reset();
                Instant packetArrival = Instant.now();
                lastPacketAt = packetArrival;
                windowPackets.incrementAndGet();
                List<KiteTickerBinaryParser.ParsedLtpTick> ticks = KiteTickerBinaryParser.parseBinaryMessage(full);
                for (KiteTickerBinaryParser.ParsedLtpTick t : ticks) {
                    windowTicks.incrementAndGet();
                    lastTickAt = packetArrival;
                    String sym = tokenSymbols.getOrDefault(t.instrumentToken(), "TOKEN_" + t.instrumentToken());
                    eventPublisher.publishEvent(new PlatformLiveTickEvent(sym, packetArrival, t.lastPricePaise(), t.instrumentToken()));
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            activeSocket.compareAndSet(webSocket, null);
            wsOpen.set(false);
            closedSinceLastOpen.set(true);
            handshakePending.set(false);
            log.warn("platform.ws.error {}", error.toString());
            telemetryService.markWebsocketClosed(VENDOR, "error: " + error.getClass().getSimpleName());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            activeSocket.compareAndSet(webSocket, null);
            wsOpen.set(false);
            closedSinceLastOpen.set(true);
            handshakePending.set(false);
            telemetryService.markWebsocketClosed(VENDOR, "closed " + statusCode + " " + reason);
            return null;
        }
    }
}
