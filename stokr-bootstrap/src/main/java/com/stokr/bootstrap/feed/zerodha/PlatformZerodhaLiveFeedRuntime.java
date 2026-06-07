package com.stokr.bootstrap.feed.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stokr.bootstrap.config.PlatformZerodhaFeedProperties;
import com.stokr.common.crypto.FieldCipher;
import com.stokr.common.events.PlatformFeedReconnectRequestedEvent;
import com.stokr.common.market.NseMarketSession;
import com.stokr.bootstrap.feed.zerodha.CdsMarketSession;
import com.stokr.strategy.domain.StrategyUniverseSymbol;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
import com.stokr.user.broker.PlatformMarketFeedService;
import com.stokr.user.broker.ZerodhaKiteApiClient;
import com.stokr.user.config.ZerodhaBrokerProperties;
import com.stokr.user.domain.PlatformBrokerFeedSession;
import com.stokr.marketdata.monitor.FeedHealthWebSocketState;
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
    private static final int NIFTY_50_TOKEN = 256265;
    private static final String NIFTY_50_SYMBOL = "NIFTY 50";
    private static final int MAX_WS_TOKENS = 3000;
    private static final long HANDSHAKE_TIMEOUT_SECONDS = 45;
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
    private final IntradaySessionGapFillService intradaySessionGapFillService;
    private final FeedHealthWebSocketState feedHealthWebSocketState;

    private final AtomicReference<WebSocket> activeSocket = new AtomicReference<>();
    private final AtomicBoolean wsOpen = new AtomicBoolean(false);
    private final AtomicBoolean closedSinceLastOpen = new AtomicBoolean(false);
    private final AtomicBoolean handshakePending = new AtomicBoolean(false);
    private final AtomicLong handshakeStartedAtMillis = new AtomicLong(0);
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
    private volatile int consecutiveHeartbeatOnlyWindows;

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

    @EventListener
    public void onReconnectRequested(PlatformFeedReconnectRequestedEvent event) {
        if (event == null || event.vendor() == null) {
            return;
        }
        if (!VENDOR.equalsIgnoreCase(event.vendor())) {
            return;
        }
        requestReconnect(event.reason() != null ? event.reason() : "admin_request");
    }

    public void requestReconnect(String reason) {
        log.info("platform.ws.reconnect_requested reason={}", reason);
        cachedSymbolMap = null;
        cachedForToken = null;
        closeActive(reason);
        resetHandshakeState();
    }

    private void resetHandshakeState() {
        handshakePending.set(false);
        handshakeStartedAtMillis.set(0);
    }

    private void releaseStaleHandshakeIfNeeded() {
        if (!handshakePending.get() || wsOpen.get()) {
            return;
        }
        long started = handshakeStartedAtMillis.get();
        if (started <= 0) {
            return;
        }
        long elapsedSec = (System.currentTimeMillis() - started) / 1000;
        if (elapsedSec < HANDSHAKE_TIMEOUT_SECONDS) {
            return;
        }
        log.warn("platform.ws.handshake_timeout elapsedSec={} — aborting and retrying", elapsedSec);
        WebSocket ws = activeSocket.getAndSet(null);
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
            }
        }
        resetHandshakeState();
        telemetryService.markWebsocketClosed(VENDOR, "handshake_timeout");
    }

    private void ensureConnectedIfNeeded() {
        releaseStaleHandshakeIfNeeded();
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
            platformMarketFeedService.ensureSessionFromTraderFallback(VENDOR);
            tokenUsable = platformMarketFeedService.ensureValidPlatformZerodhaToken(Duration.ofMinutes(30));
        }
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
            long tickAgeSec = lastTickAt == null
                    ? Long.MAX_VALUE
                    : Duration.between(lastTickAt, Instant.now()).getSeconds();
            if (tickAgeSec <= 60) {
                return;
            }
            if (!NseMarketSession.isRegularSessionOpen() && !CdsMarketSession.isCdsMarketHours(Instant.now())) {
                return;
            }
            log.warn("platform.ws.stale_ticks_reconnect tickAgeSec={} lastPacketAt={}", tickAgeSec, lastPacketAt);
            closeActive("stale_ticks");
            intradaySessionGapFillService.fillNiftySessionGapsIfNeeded("ws_stale_reconnect");
            intradaySessionGapFillService.fillUniverseSessionGapsIfNeeded("ws_stale_reconnect");
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
            universeInstrumentEnrichmentService.enrichCdsUniverseSymbols(instrumentRegistry.getSymbolToToken());
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
            handshakeStartedAtMillis.set(System.currentTimeMillis());

            telemetryService.markConnecting(VENDOR);

            String url = "wss://ws.kite.trade?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&access_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);

            WebSocket.Listener listener = new KiteListener(tokens);
            CompletableFuture<WebSocket> fut = HTTP.newWebSocketBuilder()
                    .buildAsync(URI.create(url), listener);
            fut.whenComplete((ws, err) -> {
                resetHandshakeState();
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
        List<Integer> cdsPinnedTokens = new ArrayList<>();

        if (feedProperties.isAutoSubscribeAllNse()) {
            try {
                String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "NSE");
                parseInstrumentsCsvInto(csv, "EQ", "NSE", map);
                log.info("platform.ws.auto_subscribe exchange=NSE eq_count={}", map.size());
            } catch (Exception ex) {
                log.warn("platform.ws.auto_subscribe_failed exchange=NSE {}", ex.toString());
            }
        } else if (feedProperties.isAutoSubscribeUniverseGroups()) {
            try {
                Map<Integer, String> targeted = buildUniverseDrivenMap(apiKey, accessToken);
                map.putAll(targeted);
                if (!map.isEmpty()) {
                    log.info("platform.ws.universe_subscribe groups={} tokens={}",
                            feedProperties.parsedSubscriptionUniverseGroupKeys(), map.size());
                }
            } catch (Exception ex) {
                log.warn("platform.ws.universe_subscribe_failed {}", ex.toString());
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

        if (feedProperties.isAutoSubscribeCds()) {
            try {
                String csv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "CDS");
                Map<String, Integer> pairs = CdsInstrumentResolver.resolveMajorPairs(csv);
                int before = map.size();
                pairs.forEach((canonical, token) -> {
                    map.put(token, canonical);
                    cdsPinnedTokens.add(token);
                });
                log.info("platform.ws.auto_subscribe exchange=CDS pairs={} added={}", pairs.keySet(), map.size() - before);
            } catch (Exception ex) {
                log.warn("platform.ws.auto_subscribe_failed exchange=CDS {}", ex.toString());
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

        // Hard cap — Zerodha WebSocket limit is 3000 tokens per connection.
        // Pin NIFTY 50 first; it is appended after thousands of EQ rows and was previously dropped by limit(3000).
        if (!map.containsKey(NIFTY_50_TOKEN)) {
            map.put(NIFTY_50_TOKEN, NIFTY_50_SYMBOL);
        }
        if (map.size() > MAX_WS_TOKENS) {
            List<Integer> pinned = new ArrayList<>(resolvePinnedSubscriptionTokens(map, apiKey, accessToken));
            pinned.addAll(cdsPinnedTokens);
            pinned = pinned.stream().distinct().toList();
            log.warn("platform.ws.token_cap original={} capped={} pinned_count={}",
                    map.size(), MAX_WS_TOKENS, pinned.size());
            return capWithPinnedTokens(map, MAX_WS_TOKENS, pinned);
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
        Map<Integer, String> cdsTokenToSymbol = new LinkedHashMap<>();
        String nseCsv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "NSE");
        parseInstrumentsCsvInto(nseCsv, "EQ", "NSE", nseTokenToSymbol);

        String mcxCsv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "MCX");
        parseInstrumentsCsvInto(mcxCsv, null, mcxTokenToSymbol);

        String cdsCsv = kiteApiClient.getInstrumentsCsv(apiKey, accessToken, "CDS");
        CdsInstrumentResolver.resolveMajorPairs(cdsCsv).forEach((canonical, token) -> cdsTokenToSymbol.put(token, canonical));

        Map<String, Integer> nseSymbolToToken = reverseMap(nseTokenToSymbol);
        Map<String, Integer> mcxSymbolToToken = reverseMap(mcxTokenToSymbol);
        Map<String, Integer> cdsSymbolToToken = reverseMap(cdsTokenToSymbol);
        universeInstrumentEnrichmentService.enrichMbxUniverseSymbols(mcxSymbolToToken);
        universeInstrumentEnrichmentService.enrichCdsUniverseSymbols(cdsSymbolToToken);

        Map<Integer, String> out = new LinkedHashMap<>();
        int unresolved = 0;
        int liveResolved = 0;
        int dbFallback = 0;
        for (StrategyUniverseSymbol row : universeRows) {
            String exchange = normalize(row.getExchange());
            Map<String, Integer> source = switch (exchange) {
                case "MCX" -> mcxSymbolToToken;
                case "CDS" -> cdsSymbolToToken;
                default -> nseSymbolToToken;
            };
            String preferredTrading = normalize(row.getTradingSymbol());
            String canonical = normalize(row.getSymbol());

            Integer token = null;
            String resolvedSymbol = null;
            String resolveMethod = "unresolved";
            if (!preferredTrading.isBlank()) {
                token = source.get(preferredTrading);
                resolvedSymbol = preferredTrading;
                if (token != null) resolveMethod = "exact_trading";
            }
            if (token == null && !canonical.isBlank()) {
                resolvedSymbol = chooseBestTradingSymbol(canonical, source);
                if (resolvedSymbol != null) {
                    token = source.get(resolvedSymbol);
                    if (token != null) resolveMethod = "prefix_match";
                }
            }

            // Fallback: use DB instrument_token for INDEX/non-EQ instruments (e.g. NIFTY 50)
            if (token == null && row.getInstrumentToken() != null && row.getInstrumentToken() > 0) {
                token = row.getInstrumentToken().intValue();
                resolvedSymbol = !canonical.isBlank() ? canonical : preferredTrading;
                resolveMethod = "db_fallback";
                log.info("platform.ws.universe_db_fallback symbol={} exchange={} token={}", resolvedSymbol, exchange, token);
            }

            if (token == null || token <= 0) {
                unresolved++;
                log.info("platform.ws.universe_unresolved symbol={} exchange={} tradingSymbol={}", canonical, exchange, preferredTrading);
                continue;
            }
            String storedSymbol = "CDS".equals(exchange) && !canonical.isBlank() ? canonical : resolvedSymbol;
            if ("db_fallback".equals(resolveMethod)) dbFallback++;
            else liveResolved++;
            log.info("platform.ws.universe_resolved symbol={} exchange={} token={} method={} resolvedAs={}",
                    canonical, exchange, token, resolveMethod, storedSymbol);
            out.put(token, storedSymbol != null ? storedSymbol : canonical);
        }

        log.info("platform.ws.universe_resolution_summary total={} liveResolved={} dbFallback={} unresolved={}",
                universeRows.size(), liveResolved, dbFallback, unresolved);

        // Always include NIFTY 50 as a test token to verify streaming
        if (!out.containsKey(NIFTY_50_TOKEN)) {
            out.put(NIFTY_50_TOKEN, NIFTY_50_SYMBOL);
            log.info("platform.ws.test_token_added token={} symbol={}", NIFTY_50_TOKEN, NIFTY_50_SYMBOL);
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

    private static Map<Integer, String> capWithPinnedTokens(
            Map<Integer, String> source, int maxTokens, List<Integer> pinnedTokens) {
        Map<Integer, String> capped = new LinkedHashMap<>();
        for (int token : pinnedTokens) {
            String symbol = source.get(token);
            if (symbol != null) {
                capped.put(token, symbol);
            }
        }
        for (Map.Entry<Integer, String> entry : source.entrySet()) {
            if (capped.size() >= maxTokens) {
                break;
            }
            capped.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return capped;
    }

    /**
     * Tokens that must survive the 3000-cap: NIFTY 50 index plus scan-universe EQ symbols.
     */
    private List<Integer> resolvePinnedSubscriptionTokens(
            Map<Integer, String> fullMap, String apiKey, String accessToken) {
        List<Integer> pinned = new ArrayList<>();
        pinned.add(NIFTY_50_TOKEN);

        List<String> groupKeys = feedProperties.parsedPinnedUniverseGroupKeys();
        if (groupKeys.isEmpty()) {
            return pinned.stream().distinct().toList();
        }

        List<StrategyUniverseSymbol> rows =
                strategyUniverseSymbolRepository.findAllEnabledByGroupKeys(groupKeys);
        if (rows.isEmpty()) {
            return pinned.stream().distinct().toList();
        }

        Map<String, Integer> symbolToToken = new LinkedHashMap<>();
        fullMap.forEach((token, symbol) -> symbolToToken.putIfAbsent(normalize(symbol), token));

        for (StrategyUniverseSymbol row : rows) {
            if (row == null || !row.isEnabled()) {
                continue;
            }
            String exchange = normalize(row.getExchange());
            if (!"NSE".equals(exchange) && !"CDS".equals(exchange)) {
                continue;
            }
            Integer token = null;
            if (row.getInstrumentToken() != null && row.getInstrumentToken() > 0) {
                token = row.getInstrumentToken().intValue();
            }
            if (token == null && row.getTradingSymbol() != null && !row.getTradingSymbol().isBlank()) {
                token = symbolToToken.get(normalize(row.getTradingSymbol()));
            }
            if (token == null && row.getSymbol() != null && !row.getSymbol().isBlank()) {
                token = symbolToToken.get(normalize(row.getSymbol()));
            }
            if (token != null && token > 0 && fullMap.containsKey(token)) {
                pinned.add(token);
            }
        }
        return pinned.stream().distinct().toList();
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
            resetHandshakeState();
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
        if (wsOpen.get() && tk == 0 && pk > 0) {
            consecutiveHeartbeatOnlyWindows++;
            if (consecutiveHeartbeatOnlyWindows >= 5) {
                consecutiveHeartbeatOnlyWindows = 0;
                log.warn("platform.ws.heartbeat_only_resubscribe packets={} ticks={}", pk, tk);
                resubscribeActive("heartbeat_only");
            }
        } else if (tk > 0) {
            consecutiveHeartbeatOnlyWindows = 0;
        }
    }

    private void resubscribeActive(String reason) {
        WebSocket ws = activeSocket.get();
        List<Integer> tokens = subscribedTokens;
        if (ws == null || !wsOpen.get() || tokens == null || tokens.isEmpty()) {
            return;
        }
        try {
            String sub = objectMapper.writeValueAsString(Map.of("a", "subscribe", "v", tokens));
            ws.sendText(sub, true).whenComplete((ignored, err) -> {
                if (err != null) {
                    log.warn("platform.ws.resubscribe_failed reason={} {}", reason, err.toString());
                    closeActive("resubscribe_failed");
                    return;
                }
                sendQuoteMode(ws, tokens, "resubscribe:" + reason);
            });
        } catch (Exception ex) {
            log.warn("platform.ws.resubscribe_failed reason={} {}", reason, ex.toString());
            closeActive("resubscribe_failed");
        }
    }

    private void sendQuoteMode(WebSocket webSocket, List<Integer> tokens, String context) {
        try {
            String mode = objectMapper.writeValueAsString(Map.of("a", "mode", "v", List.of("quote", tokens)));
            webSocket.sendText(mode, true).whenComplete((ignored, err) -> {
                if (err != null) {
                    log.error("platform.ws.mode_send_failed context={} {}", context, err.toString());
                } else {
                    log.info("platform.ws.mode_sent_ok context={}", context);
                }
            });
        } catch (Exception ex) {
            log.warn("platform.ws.mode_send_failed context={} {}", context, ex.toString());
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
                webSocket.sendText(sub, true).whenComplete((ignored, err) -> {
                    if (err != null) {
                        log.error("platform.ws.subscribe_send_failed {}", err.toString());
                        return;
                    }
                    log.info("platform.ws.subscribe_sent_ok");
                    sendQuoteMode(webSocket, tokens, "onOpen");
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
                if (full.length > 2) {
                    log.info("platform.ws.binary_parsed bytes={} ticks_extracted={}", full.length, ticks.size());
                    if (!ticks.isEmpty()) {
                        KiteTickerBinaryParser.ParsedLtpTick first = ticks.get(0);
                        log.info("platform.ws.first_tick token={} price={} symbol={}",
                                first.instrumentToken(), first.lastPricePaise(),
                                tokenSymbols.getOrDefault(first.instrumentToken(), "UNKNOWN"));
                    }
                }
                if (!ticks.isEmpty()) {
                    feedHealthWebSocketState.recordTick(packetArrival);
                }
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
            resetHandshakeState();
            log.warn("platform.ws.error {}", error.toString());
            telemetryService.markWebsocketClosed(VENDOR, "error: " + error.getClass().getSimpleName());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            activeSocket.compareAndSet(webSocket, null);
            wsOpen.set(false);
            closedSinceLastOpen.set(true);
            resetHandshakeState();
            telemetryService.markWebsocketClosed(VENDOR, "closed " + statusCode + " " + reason);
            return null;
        }
    }
}
