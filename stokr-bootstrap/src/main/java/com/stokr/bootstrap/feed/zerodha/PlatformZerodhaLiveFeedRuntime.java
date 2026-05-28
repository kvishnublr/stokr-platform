package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.bootstrap.config.PlatformZerodhaFeedProperties;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final InstrumentRegistryService instrumentRegistry;
    private final UniverseInstrumentEnrichmentService universeInstrumentEnrichmentService;
    private final StrategyUniverseSymbolRepository strategyUniverseSymbolRepository;
    private final ObjectMapper objectMapper;

    private final AtomicReference<WebSocket> activeSocket = new AtomicReference<>();
    private final AtomicBoolean wsOpen = new AtomicBoolean(false);
    private final AtomicBoolean closedSinceLastOpen = new AtomicBoolean(false);
    private final AtomicBoolean handshakePending = new AtomicBoolean(false);
    private final Object ensureGate = new Object();
    private final ByteArrayOutputStream binaryAcc = new ByteArrayOutputStream();
    private final AtomicLong windowPackets = new AtomicLong();
    private final AtomicLong windowTicks = new AtomicLong();
    private final AtomicLong windowUnresolvedTokens = new AtomicLong();
    private final AtomicLong windowStartNanos = new AtomicLong(System.nanoTime());
    private volatile Instant lastPacketAt;
    private volatile Instant lastTickAt;
    private volatile Instant lastHeartbeatAt;
    private volatile Map<Integer, String> tokenSymbols = Map.of();
    private volatile List<Integer> subscribedTokens = List.of();
    /** Cached symbol map — fetched once per access token, not on every 3-second poll. */
    private volatile Map<Integer, String> cachedSymbolMap = null;
    private volatile String cachedForToken = null;

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
        if ((session.getRefreshTokenEnc() == null || session.getRefreshTokenEnc().isBlank())
                && session.getTokenExpiresAt() != null) {
            long minsLeft = ChronoUnit.MINUTES.between(Instant.now(), session.getTokenExpiresAt());
            if (minsLeft <= 30) {
                log.warn("platform.ws.reauth_required_urgent minsLeft={} reason=no_refresh_token", minsLeft);
            } else if (minsLeft <= 120) {
                log.warn("platform.ws.reauth_required_soon minsLeft={} reason=no_refresh_token", minsLeft);
            }
        }
        if (session.getAccessTokenEnc() == null || session.getAccessTokenEnc().isBlank()) {
            closeActive("no_access_token");
            return;
        }
        // Auto-refresh platform token shortly before expiry to avoid daily manual admin auth.
        boolean tokenUsable = platformMarketFeedService.ensureValidPlatformZerodhaToken(Duration.ofMinutes(30));
        if (!tokenUsable) {
            closeActive("token_expired_or_refresh_failed");
            return;
        }
        session = sessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse(VENDOR).orElse(session);
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
        // Build symbol map once per access token — not on every 3-second poll
        if (cachedSymbolMap == null || !accessToken.equals(cachedForToken)) {
            cachedSymbolMap = buildSymbolMap(apiKey, accessToken);
            cachedForToken = accessToken;
            instrumentRegistry.update(cachedSymbolMap);
            universeInstrumentEnrichmentService.enrichMbxUniverseSymbols(instrumentRegistry.getSymbolToToken());
        }
        List<Integer> tokens = new ArrayList<>(cachedSymbolMap.keySet());
        this.subscribedTokens = tokens;
        this.tokenSymbols = cachedSymbolMap;

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

    /**
     * Builds the full token→symbol map for subscription.
     * When autoSubscribeAllNse=true (default), fetches every NSE EQ instrument from Zerodha's
     * instruments dump — no need to configure token lists manually.
     * Caps at 3000 tokens (Zerodha WebSocket hard limit per connection).
     */
    private Map<Integer, String> buildSymbolMap(String apiKey, String accessToken) {
        Map<Integer, String> map = new LinkedHashMap<>();

        if (feedProperties.isAutoSubscribeUniverseGroups()) {
            try {
                Map<Integer, String> targeted = buildUniverseDrivenMap(apiKey, accessToken);
                map.putAll(targeted);
                if (!map.isEmpty()) {
                    log.info("platform.ws.universe_subscribe groups={} tokens={}",
                            feedProperties.parsedSubscriptionUniverseGroupKeys(), map.size());
                    return map;
                }
            } catch (Exception ex) {
                log.warn("platform.ws.universe_subscribe_failed {}", ex.toString());
            }
        }

        if (feedProperties.isAutoSubscribeAllNse()) {
            try {
                String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "NSE");
                // segment=NSE filters main-board equities only (excludes NSE_SME, ETFs, bonds)
                parseInstrumentsCsvInto(csv, "EQ", "NSE", map);
                log.info("platform.ws.auto_subscribe exchange=NSE eq_count={}", map.size());
            } catch (Exception ex) {
                log.warn("platform.ws.auto_subscribe_failed exchange=NSE {}", ex.toString());
            }
        }

        if (feedProperties.isAutoSubscribeMcx()) {
            try {
                String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "MCX");
                int before = map.size();
                parseInstrumentsCsvInto(csv, null, map);
                log.info("platform.ws.auto_subscribe exchange=MCX added={}", map.size() - before);
            } catch (Exception ex) {
                log.warn("platform.ws.auto_subscribe_failed exchange=MCX {}", ex.toString());
            }
        }

        // Fallback: use static configured tokens when auto-subscribe is off
        if (!feedProperties.isAutoSubscribeAllNse() && !feedProperties.isAutoSubscribeMcx()) {
            List<Integer> configTokens = feedProperties.parsedInstrumentTokens();
            List<String> configSymbols = feedProperties.parsedInstrumentSymbols();
            for (int i = 0; i < configTokens.size(); i++) {
                int tok = configTokens.get(i);
                String sym = (i < configSymbols.size() && !configSymbols.get(i).isBlank())
                        ? configSymbols.get(i).trim() : "TOKEN_" + tok;
                map.put(tok, sym);
            }
            // Try to resolve any remaining TOKEN_xxx via NSE/NFO instrument dump
            if (map.values().stream().anyMatch(v -> v.startsWith("TOKEN_"))) {
                for (String exchange : List.of("NSE", "NFO")) {
                    try {
                        String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, exchange);
                        resolveUnknownTokens(csv, map);
                    } catch (Exception ex) {
                        log.debug("platform.ws.fallback_resolve exchange={} {}", exchange, ex.toString());
                    }
                    if (map.values().stream().noneMatch(v -> v.startsWith("TOKEN_"))) break;
                }
            }
            log.info("platform.ws.static_subscribe count={}", map.size());
        }

        // Hard cap — Zerodha WebSocket limit is 3000 tokens per connection
        if (map.size() > 3000) {
            Map<Integer, String> capped = new LinkedHashMap<>();
            map.entrySet().stream().limit(3000).forEach(e -> capped.put(e.getKey(), e.getValue()));
            log.warn("platform.ws.token_cap original={} capped=3000", map.size());
            return capped;
        }

        long unresolved = map.values().stream().filter(v -> v.startsWith("TOKEN_")).count();
        if (unresolved > 0) {
            log.warn("platform.ws.symbol_map_unresolved count={}", unresolved);
        } else {
            log.info("platform.ws.symbol_map_ready total={}", map.size());
        }
        return map;
    }

    private Map<Integer, String> buildUniverseDrivenMap(String apiKey, String accessToken) throws Exception {
        List<String> groupKeys = feedProperties.parsedSubscriptionUniverseGroupKeys();
        if (groupKeys.isEmpty()) {
            return Map.of();
        }

        List<StrategyUniverseSymbol> universeRows =
                strategyUniverseSymbolRepository.findAllEnabledByGroupKeys(groupKeys);
        if (universeRows.isEmpty()) {
            log.warn("platform.ws.universe_subscribe_no_rows groups={}", groupKeys);
            return Map.of();
        }

        Map<Integer, String> nseTokenToSymbol = new LinkedHashMap<>();
        Map<Integer, String> mcxTokenToSymbol = new LinkedHashMap<>();
        String nseCsv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "NSE");
        parseInstrumentsCsvInto(nseCsv, "EQ", "NSE", nseTokenToSymbol);

        String mcxCsv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "MCX");
        parseInstrumentsCsvInto(mcxCsv, null, mcxTokenToSymbol);

        Map<String, Integer> nseSymbolToToken = reverseMap(nseTokenToSymbol);
        Map<String, Integer> mcxSymbolToToken = reverseMap(mcxTokenToSymbol);
        universeInstrumentEnrichmentService.enrichMbxUniverseSymbols(mcxSymbolToToken);

        Map<Integer, String> out = new LinkedHashMap<>();
        int unresolved = 0;
        for (StrategyUniverseSymbol row : universeRows) {
            String exchange = normalize(row.getExchange());
            Map<String, Integer> source = "MCX".equals(exchange) ? mcxSymbolToToken : nseSymbolToToken;
            String preferredTrading = normalize(row.getTradingSymbol());
            String canonical = normalize(row.getSymbol());

            Integer token = null;
            String resolvedSymbol = null;
            if (!preferredTrading.isBlank()) {
                token = source.get(preferredTrading);
                resolvedSymbol = preferredTrading;
            }
            if (token == null && !canonical.isBlank()) {
                resolvedSymbol = chooseBestTradingSymbol(canonical, source);
                if (resolvedSymbol != null) {
                    token = source.get(resolvedSymbol);
                }
            }

            // Fallback: use DB instrument_token for INDEX/non-EQ instruments (e.g. NIFTY 50)
            if (token == null && row.getInstrumentToken() != null && row.getInstrumentToken() > 0) {
                token = row.getInstrumentToken().intValue();
                resolvedSymbol = !canonical.isBlank() ? canonical : preferredTrading;
                log.debug("platform.ws.universe_subscribe_db_fallback symbol={} token={}", resolvedSymbol, token);
            }

            if (token == null || token <= 0) {
                unresolved++;
                continue;
            }
            out.put(token, resolvedSymbol != null ? resolvedSymbol : canonical);
        }

        if (unresolved > 0) {
            log.warn("platform.ws.universe_subscribe_unresolved rows={} unresolved={}", universeRows.size(), unresolved);
        }
        return out;
    }

    private static Map<String, Integer> reverseMap(Map<Integer, String> tokenMap) {
        Map<String, Integer> out = new LinkedHashMap<>();
        tokenMap.forEach((token, symbol) -> out.put(normalize(symbol), token));
        return out;
    }

    private static String chooseBestTradingSymbol(String canonical, Map<String, Integer> symbolToToken) {
        String exact = symbolToToken.keySet().stream()
                .filter(k -> normalize(k).equals(canonical))
                .findFirst()
                .orElse(null);
        if (exact != null) return exact;
        return symbolToToken.keySet().stream()
                .filter(k -> normalize(k).startsWith(canonical))
                .min(java.util.Comparator.comparingInt(String::length))
                .orElse(null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void parseInstrumentsCsvInto(String csv, String typeFilter, Map<Integer, String> out) throws Exception {
        parseInstrumentsCsvInto(csv, typeFilter, null, out);
    }

    private void parseInstrumentsCsvInto(String csv, String typeFilter, String segmentFilter, Map<Integer, String> out) throws Exception {
        BufferedReader br = new BufferedReader(new StringReader(csv));
        String header = br.readLine();
        if (header == null) return;
        String[] hc = parseCsvLine(header);
        int itCol = -1, tsCol = -1, typeCol = -1, segCol = -1;
        for (int i = 0; i < hc.length; i++) {
            String c = hc[i].trim();
            if ("instrument_token".equalsIgnoreCase(c)) itCol = i;
            if ("tradingsymbol".equalsIgnoreCase(c)) tsCol = i;
            if ("instrument_type".equalsIgnoreCase(c)) typeCol = i;
            if ("segment".equalsIgnoreCase(c)) segCol = i;
        }
        if (itCol < 0 || tsCol < 0) return;
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = parseCsvLine(line);
            if (p.length <= Math.max(itCol, tsCol)) continue;
            if (typeFilter != null && typeCol >= 0
                    && !typeFilter.equalsIgnoreCase(p[typeCol].trim())) continue;
            if (segmentFilter != null && segCol >= 0
                    && !segmentFilter.equalsIgnoreCase(p[segCol].trim())) continue;
            try {
                int tok = (int) Long.parseLong(p[itCol].trim());
                String sym = p[tsCol].trim();
                if (!sym.isBlank()) out.put(tok, sym);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void resolveUnknownTokens(String csv, Map<Integer, String> map) throws Exception {
        BufferedReader br = new BufferedReader(new StringReader(csv));
        String header = br.readLine();
        if (header == null) return;
        String[] hc = parseCsvLine(header);
        int itCol = -1, tsCol = -1;
        for (int i = 0; i < hc.length; i++) {
            String c = hc[i].trim();
            if ("instrument_token".equalsIgnoreCase(c)) itCol = i;
            if ("tradingsymbol".equalsIgnoreCase(c)) tsCol = i;
        }
        if (itCol < 0 || tsCol < 0) return;
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = parseCsvLine(line);
            if (p.length <= Math.max(itCol, tsCol)) continue;
            try {
                int tok = (int) Long.parseLong(p[itCol].trim());
                if (map.containsKey(tok) && map.get(tok).startsWith("TOKEN_")) {
                    String sym = p[tsCol].trim();
                    if (!sym.isBlank()) map.put(tok, sym);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private String[] parseCsvLine(String line) {
        if (line == null || line.isEmpty()) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        out.add(cell.toString());
        return out.toArray(new String[0]);
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
        long unresolvedTokens = windowUnresolvedTokens.getAndSet(0);
        windowStartNanos.set(System.nanoTime());
        double pps = pk / (double) elapsed;
        double tps = tk / (double) elapsed;
        int subs = subscribedTokens.size();
        String wsState = wsOpen.get() ? "OPEN" : "CLOSED";
        // Store a compact summary instead of a 2000-symbol CSV to keep the DB row small
        String streamingSymbolsCsv = subs + " instruments subscribed";
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
        if (unresolvedTokens > 0) {
            log.warn("platform.ws.unresolved_tokens_window count={}", unresolvedTokens);
        }
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
            telemetryService.markWebsocketOpen(VENDOR);
            log.info("platform.ws.onOpen tokens_to_subscribe={}", tokens.size());
            try {
                String sub = objectMapper.writeValueAsString(Map.of("a", "subscribe", "v", tokens));
                log.info("platform.ws.subscribe_msg length={} first_100_chars={}", sub.length(),
                        sub.length() > 100 ? sub.substring(0, 100) + "..." : sub);
                webSocket.sendText(sub, true).whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("platform.ws.subscribe_send_failed {}", err.toString());
                    } else {
                        log.info("platform.ws.subscribe_sent_ok");
                    }
                });
                // "quote" mode (44 bytes/tick) gives us LTP + lastTradedQty + volumeTraded + OHLC
                // "ltp" mode (8 bytes) only gives price — no volume data for candles
                String mode = objectMapper.writeValueAsString(Map.of("a", "mode", "v", List.of("quote", tokens)));
                log.info("platform.ws.mode_msg length={} first_100_chars={}", mode.length(),
                        mode.length() > 100 ? mode.substring(0, 100) + "..." : mode);
                webSocket.sendText(mode, true).whenComplete((ws, err) -> {
                    if (err != null) {
                        log.error("platform.ws.mode_send_failed {}", err.toString());
                    } else {
                        log.info("platform.ws.mode_sent_ok");
                    }
                });
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
                    log.info("platform.ws.text_received length={} content={}", s.length(),
                            s.length() > 300 ? s.substring(0, 300) + "..." : s);
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
            log.info("platform.ws.binary_received bytes={} last={}", message.remaining(), last);
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
                    if (sym.startsWith("TOKEN_")) {
                        windowUnresolvedTokens.incrementAndGet();
                    }
                    eventPublisher.publishEvent(new PlatformLiveTickEvent(
                            sym, packetArrival, t.lastPricePaise(), t.instrumentToken(),
                            t.lastTradedQuantity(), t.volumeTraded(),
                            t.totalBuyQuantity(), t.totalSellQuantity()));
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
