package com.stokr.marketdata.tick;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.engine.BrokerTokenRefresher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class KiteTickWebSocketClient {

    private static final String WS_URL = "wss://ws.kite.trade/";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    private final BrokerAccountRepository brokerAccountRepo;
    private final TickAggregatorService aggregator;
    private final BrokerTokenRefresher tokenRefresher;

    private WebSocket webSocket;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong tickCounter = new AtomicLong(0);

    private volatile boolean connected = false;
    private volatile long lastAutoReconnectAttemptMs = 0L;
    private final Set<Integer> subscribedTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();
    private final List<java.util.function.Consumer<TickData>> extraListeners = new CopyOnWriteArrayList<>();

    /** Register to receive every parsed tick, in addition to the built-in candle aggregator. */
    public void addTickListener(java.util.function.Consumer<TickData> listener) {
        extraListeners.add(listener);
    }

    @PostConstruct
    public void init() {
        reconnectExecutor.scheduleWithFixedDelay(this::ensureConnected, 5, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        connected = false;
        reconnectExecutor.shutdown();
        if (webSocket != null) webSocket.abort();
    }

    public void subscribe(String symbol, int instrumentToken) {
        subscribedSymbols.add(symbol);
        subscribedTokens.add(instrumentToken);
        KiteTickParser.addMapping(instrumentToken, symbol);
        if (connected) {
            sendSubscribe(List.of(instrumentToken));
            sendMode("full", List.of(instrumentToken));
        }
    }

    public void subscribeBatch(Map<String, Integer> symbolTokenMap) {
        subscribedSymbols.addAll(symbolTokenMap.keySet());
        subscribedTokens.addAll(symbolTokenMap.values());
        Map<Integer, String> reversed = new HashMap<>();
        symbolTokenMap.forEach((s, t) -> reversed.put(t, s));
        KiteTickParser.addMappings(reversed);
        if (connected) {
            List<Integer> tokens = new ArrayList<>(symbolTokenMap.values());
            sendSubscribe(tokens);
            sendMode("full", tokens);
        }
    }

    public void ensureConnected() {
        if (connected) return;
        try {
            String token = resolveAccessToken();
            if (token == null) {
                log.warn("No active Kite token found in DB");
                return;
            }
            String uid = String.valueOf(System.currentTimeMillis());
            String maskedKey = apiKey.length() > 4 ? apiKey.substring(0, 4) + "..." : "(empty)";
            log.info("Connecting Kite WebSocket (apiKey={}, tokenLen={})", maskedKey, token.length());
            String wsUrl = WS_URL + "?api_key=" + apiKey + "&access_token=" + token + "&uid=" + uid;

            CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .header("X-Kite-Version", "3")
                .header("User-Agent", "stokr-lite/1.0")
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder messageBuf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        log.info("Kite WS connection opened (auth via URL params)");
                        WebSocket.Listener.super.onOpen(ws);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        messageBuf.append(data);
                        if (last) {
                            String json = messageBuf.toString();
                            messageBuf.setLength(0);
                            handleTextMessage(json);
                        }
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
                        handleBinaryMessage(data);
                        return WebSocket.Listener.super.onBinary(ws, data, last);
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("WebSocket error: {}", error.getMessage());
                        connected = false;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        log.warn("WebSocket closed: {} {}", statusCode, reason);
                        connected = false;
                        return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                    }
                });

            webSocket = wsFuture.get(10, TimeUnit.SECONDS);
            connected = true;
            log.info("Kite WebSocket connected");

            if (!subscribedTokens.isEmpty()) {
                sendSubscribe(new ArrayList<>(subscribedTokens));
                sendMode("full", new ArrayList<>(subscribedTokens));
            } else {
                subscribe("RELIANCE", 738561);
            }

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof java.net.http.WebSocketHandshakeException hse) {
                var resp = hse.getResponse();
                int status = resp != null ? resp.statusCode() : -1;
                log.warn("WebSocket handshake failed: status={}, body={}",
                    status >= 0 ? status : "null",
                    resp != null ? resp.body() : "null");
                if (status == 403) {
                    maybeTriggerAutoReconnect("ws-handshake-403");
                }
            } else {
                log.warn("WebSocket connect failed: {}", cause != null ? cause.getMessage() : e.getMessage());
            }
            connected = false;
        } catch (Exception e) {
            log.warn("WebSocket connect failed (retry in 30s): {}: {}", e.getClass().getSimpleName(), e.getMessage());
            connected = false;
        }
    }

    private void handleTextMessage(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = mapper.readValue(json, Map.class);
            String type = (String) msg.getOrDefault("type", msg.get("a"));
            if ("error".equals(type)) {
                log.error("Kite WS error: {}", json);
            } else if ("ping".equals(type)) {
            } else {
                log.debug("Kite WS message: type={}", type);
            }
        } catch (Exception e) {
            log.warn("Failed to parse WS text message: {}", e.getMessage());
        }
    }

    private void handleBinaryMessage(ByteBuffer buffer) {
        long count = tickCounter.incrementAndGet();
        try {
            List<TickData> ticks = KiteTickParser.parse(buffer, subscribedSymbols);
            for (TickData tick : ticks) {
                aggregator.onTick(tick);
                for (var listener : extraListeners) {
                    try {
                        listener.accept(tick);
                    } catch (Exception e) {
                        log.debug("Tick listener failed: {}", e.getMessage());
                    }
                }
            }
            if (count % 1000 == 0) {
                log.debug("Processed {} ticks", count);
            }
        } catch (Exception e) {
            log.warn("Failed to parse binary tick: {}", e.getMessage());
        }
    }

    private void sendSubscribe(List<Integer> tokens) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("a", "subscribe");
            msg.put("v", tokens);
            webSocket.sendText(mapper.writeValueAsString(msg), true);
        } catch (Exception e) {
            log.error("Failed to send subscribe", e);
        }
    }

    private void sendMode(String mode, List<Integer> tokens) {
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("a", "mode");
            msg.put("v", List.of(mode, tokens));
            webSocket.sendText(mapper.writeValueAsString(msg), true);
        } catch (Exception e) {
            log.error("Failed to send mode", e);
        }
    }

    private String resolveAccessToken() {
        return brokerAccountRepo.findByBrokerNameAndStatus("ZERODHA", "ACTIVE")
            .stream().filter(a -> a.getAccessToken() != null)
            .findFirst().map(a -> a.getAccessToken())
            .orElse(null);
    }

    private void maybeTriggerAutoReconnect(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastAutoReconnectAttemptMs < 10 * 60 * 1000L) {
            return;
        }
        lastAutoReconnectAttemptMs = now;
        try {
            var accounts = brokerAccountRepo.findByBrokerNameAndStatus("ZERODHA", "ACTIVE");
            for (var acc : accounts) {
                if (Boolean.TRUE.equals(acc.getAutoReconnect())
                        && acc.getZerodhaPassword() != null
                        && acc.getZerodhaTotpSecret() != null) {
                    log.warn("Attempting automatic Zerodha reconnect due to {} for account {}", reason, acc.getId());
                    String result = tokenRefresher.triggerManualReconnect(acc.getId());
                    log.warn("Automatic Zerodha reconnect result for account {}: {}", acc.getId(), result);
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("Automatic Zerodha reconnect attempt failed: {}", ex.getMessage());
        }
    }

    public boolean isConnected() { return connected; }
    public long getTickCount() { return tickCounter.get(); }
}
