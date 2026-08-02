package com.stokr.arbitrage;

import com.stokr.broker.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bid-parity / option-arb auto execution (broker-routed).
 * Persists settings to option_arb_auto_exec_settings and executes 3-leg hedges (CE+PE+FUT).
 * Never fires without a live broker AvailableMargin check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionArbAutoExecService {

    /** Approximate SPAN+exposure for hedged conversion/reversal (CE+PE+FUT), not naked futures. */
    private static final Map<String, Double> HEDGED_MARGIN = Map.of(
            "NIFTY", 75000.0,
            "BANKNIFTY", 120000.0,
            "MIDCPNIFTY", 90000.0,
            "FINNIFTY", 90000.0
    );

    /** Refuse to trade if broker reports less than this free margin (₹). */
    private static final double MIN_AVAILABLE_MARGIN = 5_000.0;
    /** Keep this fraction of free margin unused as buffer. */
    private static final double MARGIN_USAGE_CAP = 0.85;

    private final OptionArbOpportunityRepository oppRepo;
    private final LivePositionRepository positionRepo;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;
    private final OptionChainService optionChainService;
    private final AutoExecSettingRepository settingsRepo;

    private final ConcurrentHashMap<String, Object> settings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> execLogs = Collections.synchronizedList(new ArrayList<>());

    /** Dedicated pool for parallel 3-leg placement (low latency). */
    private final ExecutorService execPool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "bid-parity-exec");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong marginCacheAt = new AtomicLong(0);
    private volatile double marginCacheValue = -1;
    private static final long MARGIN_CACHE_MS = 2500;

    @PostConstruct
    public void init() {
        applyDefaults();
        loadFromDb();
        log.info("Loaded {} auto-exec settings from DB (enabled={}, broker={})",
                settings.size(), settings.get("enabled"), settings.get("broker"));
    }

    private void applyDefaults() {
        settings.put("enabled", false);
        settings.put("broker", "NAVIA");
        settings.put("niftyEnabled", false);
        settings.put("niftyMinEdge", 2000.0);
        settings.put("niftyLots", 1);
        settings.put("bankniftyEnabled", false);
        settings.put("bankniftyMinEdge", 2000.0);
        settings.put("bankniftyLots", 1);
        settings.put("finniftyEnabled", false);
        settings.put("finniftyMinEdge", 2000.0);
        settings.put("finniftyLots", 1);
        settings.put("midcpniftyEnabled", false);
        settings.put("midcpniftyMinEdge", 2000.0);
        settings.put("midcpniftyLots", 1);
        settings.put("maxOpenPositions", 3);
        settings.put("maxDailyLoss", 5000.0);
        settings.put("strategyFilter", "PARITY");
    }

    private void loadFromDb() {
        try {
            for (AutoExecSetting row : settingsRepo.findAllByOrderBySettingKey()) {
                parseAndPut(row.getSettingKey(), row.getSettingValue());
            }
            applyLegacyAliases();
        } catch (Exception e) {
            log.warn("Could not load auto-exec settings from DB: {}", e.getMessage());
        }
    }

    /**
     * Map production legacy keys onto the camelCase keys this service uses.
     * Does not overwrite an explicit camelCase value already present.
     */
    private void applyLegacyAliases() {
        // enabled ← bid_parity_auto_enabled / auto_execute_enabled (only if enabled never set from those)
        Object enabledRaw = settings.get("bid_parity_auto_enabled");
        if (enabledRaw != null) {
            settings.put("enabled", parseBool(String.valueOf(enabledRaw)));
        }
        // Prefer explicit "enabled" row if both exist — re-read from map after parseAndPut already set it
        // parseAndPut already put "enabled" if key was "enabled". Legacy overwrites only when enabled still default false
        // and bid_parity says true — handled below carefully:
        if (settings.containsKey("bid_parity_auto_enabled")) {
            boolean legacyOn = parseBool(String.valueOf(settings.get("bid_parity_auto_enabled")));
            // Keep whichever is more explicit: if camelCase enabled was loaded as string/bool from DB it is already set.
            // If only legacy exists, sync it.
            if (!settings.containsKey("enabled") || !Boolean.TRUE.equals(settings.get("enabled"))) {
                settings.put("enabled", legacyOn);
            }
        }

        if (settings.containsKey("max_total_positions") && !settings.containsKey("maxOpenPositions_from_db")) {
            try {
                settings.put("maxOpenPositions", Integer.parseInt(String.valueOf(settings.get("max_total_positions")).replace(".0", "")));
            } catch (Exception ignored) {}
        }
        if (settings.containsKey("min_edge_after_costs") || settings.containsKey("scanner_minEdgeAfterCosts")) {
            Object v = settings.getOrDefault("scanner_minEdgeAfterCosts", settings.get("min_edge_after_costs"));
            try {
                double edge = Double.parseDouble(String.valueOf(v));
                settings.putIfAbsent("niftyMinEdge", edge);
                settings.putIfAbsent("bankniftyMinEdge", edge);
                settings.putIfAbsent("finniftyMinEdge", edge);
                settings.putIfAbsent("midcpniftyMinEdge", edge);
            } catch (Exception ignored) {}
        }
        if (settings.containsKey("target_underlying")) {
            String targets = String.valueOf(settings.get("target_underlying")).toUpperCase(Locale.ROOT);
            for (String u : List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
                String k = u.toLowerCase(Locale.ROOT) + "Enabled";
                // Only auto-enable from target list when legacy auto is on AND camelCase not explicitly false from DB
                if (targets.contains(u) && Boolean.TRUE.equals(settings.get("enabled"))) {
                    if (!settings.containsKey(k) || !(settings.get(k) instanceof Boolean)) {
                        settings.put(k, true);
                    }
                }
            }
        }
    }

    private void parseAndPut(String key, String value) {
        if (key == null || value == null) return;
        String k = key.trim();
        String v = value.trim();

        // Always keep raw legacy keys for alias pass
        if (k.contains("_") || k.startsWith("scanner_") || k.startsWith("bid_parity") || k.startsWith("auto_")) {
            settings.put(k, v);
        }

        if ("enabled".equals(k) || k.endsWith("Enabled") || "bid_parity_auto_enabled".equals(k)
                || "auto_execute_enabled".equals(k)) {
            boolean b = parseBool(v);
            if ("bid_parity_auto_enabled".equals(k) || "auto_execute_enabled".equals(k)) {
                settings.put(k, v); // keep raw
                // Do not set enabled here — applyLegacyAliases decides; but store boolean form too
                settings.put(k + "_bool", b);
            } else {
                settings.put(k, b);
            }
        } else if (k.endsWith("MinEdge") || "maxDailyLoss".equals(k) || "min_edge_after_costs".equals(k)
                || "scanner_minEdgeAfterCosts".equals(k)) {
            try { settings.put(k, Double.parseDouble(v)); } catch (Exception ignored) {}
        } else if (k.endsWith("Lots") || "maxOpenPositions".equals(k) || "max_total_positions".equals(k)
                || "max_positions_per_underlying".equals(k)) {
            try { settings.put(k, Integer.parseInt(v.replace(".0", ""))); } catch (Exception ignored) {}
        } else {
            settings.put(k, v);
        }
    }

    static boolean parseBool(String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "1.0".equals(v);
    }

    public Map<String, Object> getSettings() {
        Map<String, Object> out = new LinkedHashMap<>(settings);
        out.put("enabled", Boolean.TRUE.equals(settings.get("enabled")));
        out.put("broker", settings.getOrDefault("broker", "NAVIA"));
        out.put("availableMarginGate", MIN_AVAILABLE_MARGIN);
        out.put("marginUsageCap", MARGIN_USAGE_CAP);
        return out;
    }

    @Transactional
    public void updateSetting(String key, String value) {
        parseAndPut(key, value);
        if ("bid_parity_auto_enabled".equals(key) || "enabled".equals(key)) {
            boolean on = parseBool(value);
            settings.put("enabled", on);
            // Keep both keys in sync in DB
            upsertDb("enabled", on ? "true" : "false");
            upsertDb("bid_parity_auto_enabled", on ? "1" : "0");
            addLog("SETTINGS", "INFO", "Updated enabled=" + on);
            return;
        }
        AutoExecSetting row = settingsRepo.findBySettingKey(key).orElseGet(AutoExecSetting::new);
        row.setSettingKey(key);
        row.setSettingValue(value);
        settingsRepo.save(row);
        applyLegacyAliases();
        addLog("SETTINGS", "INFO", "Updated '" + key + "' = " + value);
    }

    private void upsertDb(String key, String value) {
        AutoExecSetting row = settingsRepo.findBySettingKey(key).orElseGet(AutoExecSetting::new);
        row.setSettingKey(key);
        row.setSettingValue(value);
        settingsRepo.save(row);
        settings.put(key, "enabled".equals(key) ? parseBool(value) : value);
    }

    public List<Map<String, Object>> getExecLogs() {
        List<Map<String, Object>> list = new ArrayList<>(execLogs);
        Collections.reverse(list);
        return list.stream().limit(100).toList();
    }

    /**
     * Called after scan saves new opportunities.
     * HARD RULE: fetch live broker AvailableMargin; skip if insufficient.
     */
    public void evaluateAndExecute(List<OptionArbOpportunity> newOpps) {
        applyLegacyAliases();
        if (!Boolean.TRUE.equals(settings.get("enabled"))) {
            log.debug("Auto-exec disabled — skip");
            return;
        }

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 16)) || nowIST.isAfter(LocalTime.of(15, 25))) {
            addLog("TIME", "SKIP", "Outside auto-exec window 09:16–15:25 IST");
            return;
        }

        String broker = String.valueOf(settings.getOrDefault("broker", "NAVIA")).toUpperCase(Locale.ROOT);
        int maxPositions = ((Number) settings.getOrDefault("maxOpenPositions", 3)).intValue();
        long currentOpen = positionRepo.countAllOpen();
        if (currentOpen >= maxPositions) {
            addLog("RISK", "SKIP", "Max open positions reached: " + currentOpen);
            return;
        }

        BrokerAccount account;
        BrokerAdapter adapter;
        Long userId;
        try {
            account = resolveBrokerAccount(broker);
            if (account == null) {
                addLog("BROKER", "ERROR", "No ACTIVE " + broker + " account");
                return;
            }
            userId = account.getUserId();
            adapter = brokerService.getAdapter(broker);

            // Refresh Navia session before margin/order calls
            if ("NAVIA".equals(broker) && adapter instanceof NaviaAdapter navia) {
                try {
                    String fresh = navia.loginWithTotp(account);
                    account.setAccessToken(fresh);
                    brokerAccountRepo.save(account);
                } catch (Exception e) {
                    addLog("BROKER", "ERROR", "Navia re-login failed: " + e.getMessage());
                    return;
                }
            }
        } catch (Exception e) {
            log.error("Auto-exec: broker setup failed: {}", e.getMessage());
            addLog("BROKER", "ERROR", e.getMessage());
            return;
        }

        double availableMargin = fetchAvailableMarginOrAbort(adapter, account);
        if (availableMargin < 0) return; // aborted
        if (availableMargin < MIN_AVAILABLE_MARGIN) {
            addLog("MARGIN", "BLOCKED", "AvailableMargin ₹" + String.format("%.0f", availableMargin)
                    + " < minimum ₹" + String.format("%.0f", MIN_AVAILABLE_MARGIN) + " — no trades");
            return;
        }

        double maxDailyLoss = ((Number) settings.getOrDefault("maxDailyLoss", 5000.0)).doubleValue();
        double todayPnl = positionRepo.findAllOpen().stream()
                .filter(p -> p.getCurrentPnl() != null)
                .mapToDouble(p -> p.getCurrentPnl().doubleValue())
                .sum();
        if (todayPnl < -maxDailyLoss) {
            addLog("RISK", "STOPPED", "Daily loss limit hit: ₹" + String.format("%.0f", todayPnl));
            return;
        }

        String strategyFilter = String.valueOf(settings.getOrDefault("strategyFilter", "PARITY"));

        List<OptionArbOpportunity> ranked = newOpps.stream()
                .filter(o -> o.getUnderlying() != null && o.getEdgeAfterCosts() != null && o.getStrike() != null)
                .sorted((a, b) -> Double.compare(
                        b.getEdgeAfterCosts().doubleValue(),
                        a.getEdgeAfterCosts().doubleValue()))
                .toList();

        addLog("MARGIN", "OK", "Broker=" + broker + " AvailableMargin=₹"
                + String.format("%.0f", availableMargin) + " candidates=" + ranked.size());

        for (OptionArbOpportunity opp : ranked) {
            if (currentOpen >= maxPositions) break;

            String key = opp.getUnderlying().toLowerCase(Locale.ROOT);
            if (!Boolean.TRUE.equals(settings.get(key + "Enabled"))) continue;

            String stratType = opp.getStrategyType() != null ? opp.getStrategyType().toUpperCase(Locale.ROOT) : "";
            if ("PARITY".equalsIgnoreCase(strategyFilter)
                    && !stratType.contains("PARITY") && !stratType.contains("BID")) continue;
            if ("BOX".equalsIgnoreCase(strategyFilter) && !stratType.contains("BOX")) continue;

            double minEdge = ((Number) settings.getOrDefault(key + "MinEdge", 2000.0)).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() < minEdge) continue;
            if (opp.getExpiryDate() == null) continue;

            String action = normalizeAction(opp.getAction());
            if (action == null) {
                addLog("SIGNAL", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " unsupported action: " + opp.getAction());
                continue;
            }

            if (positionRepo.findByUserIdAndStatusOrderByEnteredAtDesc(userId, "OPEN").stream()
                    .anyMatch(p -> Objects.equals(opp.getId(), p.getOpportunityId())
                            || (Objects.equals(p.getUnderlying(), opp.getUnderlying())
                            && Objects.equals(p.getStrike(), opp.getStrike())
                            && "OPEN".equals(p.getStatus())))) {
                continue;
            }

            // Use short-lived margin cache inside a cycle (re-check if stale)
            double liveMargin = fetchAvailableMarginCached(adapter, account);
            if (liveMargin < 0) return;
            availableMargin = liveMargin;
            if (availableMargin < MIN_AVAILABLE_MARGIN) {
                addLog("MARGIN", "BLOCKED", "Live AvailableMargin ₹" + String.format("%.0f", availableMargin)
                        + " below floor — stopping cycle");
                return;
            }

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            double required = estimateHedgedMargin(opp.getUnderlying(), lots);
            double usable = availableMargin * MARGIN_USAGE_CAP;
            if (required > usable) {
                addLog("MARGIN", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " needs ₹" + String.format("%.0f", required)
                        + " but usable ₹" + String.format("%.0f", usable)
                        + " (avail ₹" + String.format("%.0f", availableMargin) + " × "
                        + String.format("%.0f", MARGIN_USAGE_CAP * 100) + "%)");
                continue;
            }

            addLog("SIGNAL", "FIRING", opp.getUnderlying() + " " + opp.getStrike()
                    + " " + action + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " ≥ ₹" + String.format("%.0f", minEdge)
                    + " | margin ₹" + String.format("%.0f", availableMargin));
            long tExec = System.currentTimeMillis();
            executeTradeFast(account, adapter, opp, lots, userId, action);
            addLog("EXEC", "LATENCY", opp.getUnderlying() + " " + opp.getStrike()
                    + " place+confirm " + (System.currentTimeMillis() - tExec) + "ms");
            // Invalidate margin cache after a live fire
            marginCacheAt.set(0);
            currentOpen++;
        }
    }

    /** @return margin amount, or -1 if fetch failed (caller must abort). */
    private double fetchAvailableMarginOrAbort(BrokerAdapter adapter, BrokerAccount account) {
        try {
            BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
            if (margin == null) {
                addLog("MARGIN", "ERROR", "Broker returned null AvailableMargin — abort");
                return -1;
            }
            marginCacheValue = margin.doubleValue();
            marginCacheAt.set(System.currentTimeMillis());
            return marginCacheValue;
        } catch (Exception e) {
            addLog("MARGIN", "ERROR", "Failed to fetch AvailableMargin: " + e.getMessage() + " — abort");
            return -1;
        }
    }

    private double fetchAvailableMarginCached(BrokerAdapter adapter, BrokerAccount account) {
        long now = System.currentTimeMillis();
        if (marginCacheValue >= 0 && (now - marginCacheAt.get()) < MARGIN_CACHE_MS) {
            return marginCacheValue;
        }
        return fetchAvailableMarginOrAbort(adapter, account);
    }

    private BrokerAccount resolveBrokerAccount(String broker) {
        // Prefer ACTIVE account for the requested broker (any user), then filter
        List<BrokerAccount> byBroker = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
        if (!byBroker.isEmpty()) return byBroker.get(0);

        // Fallback: first ACTIVE user that has this broker
        return brokerAccountRepo.findByStatus("ACTIVE").stream()
                .map(BrokerAccount::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .map(uid -> brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(uid, broker, "ACTIVE"))
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .findFirst()
                .orElse(null);
    }

    /** Map legacy labels to CONVERSION / REVERSAL. */
    static String normalizeAction(String raw) {
        if (raw == null) return null;
        String a = raw.toUpperCase(Locale.ROOT);
        if (a.equals("BUY CE+PE / SELL FUT") || a.equals("BUY FUT / SELL CE+PE")) {
            return null; // ambiguous legacy — never live-fire
        }
        if (a.contains("LONG BOX") || a.contains("SHORT BOX") || a.contains("BOX")) {
            return null; // 4-leg box — not handled by 3-leg parity executor
        }
        if (a.contains("CONVERSION") || a.contains("BUY CE / SELL PE")) return "CONVERSION";
        if (a.contains("REVERSAL") || a.contains("SELL CE / BUY PE")) return "REVERSAL";
        if (a.contains("BUY CE") && a.contains("SELL PE") && a.contains("SELL") && a.contains("FUT")) return "CONVERSION";
        if (a.contains("SELL CE") && a.contains("BUY PE") && a.contains("BUY") && a.contains("FUT")) return "REVERSAL";
        return null;
    }

    private void executeTradeFast(BrokerAccount account, BrokerAdapter adapter, OptionArbOpportunity opp,
                                  int lots, Long userId, String action) {
        int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
        LocalDate expiry = opp.getExpiryDate();
        String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), expiry, opp.getStrike(), "CE");
        String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), expiry, opp.getStrike(), "PE");
        String futSymbol = buildMonthlyFutSymbol(opp.getUnderlying());

        LivePosition position = LivePosition.builder()
                .userId(userId)
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(action)
                .strategyType(opp.getStrategyType())
                .ceSymbol(ceSymbol)
                .peSymbol(peSymbol)
                .futSymbol(futSymbol)
                .lots(lots)
                .lotSize(lotSize)
                .ceEntryPrice(opp.getCeEntryPrice())
                .peEntryPrice(opp.getPeEntryPrice())
                .targetEdge(opp.getEdgeAfterCosts())
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        // Persist EXECUTING immediately so UI/ops can see in-flight
        positionRepo.save(position);

        boolean conversion = "CONVERSION".equals(action);
        // Navia F&O qty is in LOTS; Zerodha/others use units (lots * lotSize).
        boolean naviaLots = adapter instanceof NaviaAdapter
                || "NAVIA".equalsIgnoreCase(adapter.getBrokerName());
        int qty = naviaLots ? Math.max(1, lots) : lots * lotSize;
        String token = account.getAccessToken();
        addLog("EXEC", "QTY", opp.getUnderlying() + " broker=" + adapter.getBrokerName()
                + " lots=" + lots + " lotSize=" + lotSize + " orderQty=" + qty
                + (naviaLots ? " (Navia lots)" : " (units)"));

        BrokerOrderRequest.Side ceSide = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;
        BrokerOrderRequest.Side peSide = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
        BrokerOrderRequest.Side futSide = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;

        try {
            // Fire all 3 legs in parallel for minimum hedge latency
            CompletableFuture<BrokerOrderResponse> ceF = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, ceSymbol, ceSide, qty), execPool);
            CompletableFuture<BrokerOrderResponse> peF = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, peSymbol, peSide, qty), execPool);
            CompletableFuture<BrokerOrderResponse> futF = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, futSymbol, futSide, qty), execPool);

            CompletableFuture.allOf(ceF, peF, futF).get(8, TimeUnit.SECONDS);
            BrokerOrderResponse ceResp = ceF.getNow(null);
            BrokerOrderResponse peResp = peF.getNow(null);
            BrokerOrderResponse futResp = futF.getNow(null);

            boolean ceOk = isPlaced(ceResp);
            boolean peOk = isPlaced(peResp);
            boolean futOk = isPlaced(futResp);

            if (ceOk) position.setCeOrderId(ceResp.orderId());
            if (peOk) position.setPeOrderId(peResp.orderId());
            if (futOk) position.setFutOrderId(futResp.orderId());

            if (ceOk && peOk && futOk) {
                position.setStatus("OPEN");
                positionRepo.save(position);
                addLog("EXEC", "SUCCESS", opp.getUnderlying() + " " + opp.getStrike() + " " + action
                        + " lots=" + lots + " PARALLEL"
                        + " CE:" + ceResp.orderId() + " PE:" + peResp.orderId() + " FUT:" + futResp.orderId());
                return;
            }

            // Partial — square off whatever filled, in parallel
            List<CompletableFuture<BrokerOrderResponse>> unwind = new ArrayList<>();
            if (ceOk) unwind.add(CompletableFuture.supplyAsync(
                    () -> place(adapter, token, ceSymbol, flip(ceSide), qty), execPool));
            if (peOk) unwind.add(CompletableFuture.supplyAsync(
                    () -> place(adapter, token, peSymbol, flip(peSide), qty), execPool));
            if (futOk) unwind.add(CompletableFuture.supplyAsync(
                    () -> place(adapter, token, futSymbol, flip(futSide), qty), execPool));
            if (!unwind.isEmpty()) {
                try {
                    CompletableFuture.allOf(unwind.toArray(CompletableFuture[]::new)).get(8, TimeUnit.SECONDS);
                } catch (Exception unwindEx) {
                    addLog("EXEC", "UNWIND_ERR", unwindEx.getMessage());
                }
            }

            String err = "CE=" + statusOf(ceResp) + " PE=" + statusOf(peResp) + " FUT=" + statusOf(futResp);
            if (!ceOk && !peOk && !futOk) {
                position.setStatus("FAILED");
                position.setErrorMessage(err);
                addLog("EXEC", "FAILED", opp.getUnderlying() + " " + err);
            } else {
                position.setStatus("PARTIAL");
                position.setErrorMessage(err + " (unwind attempted)");
                addLog("EXEC", "PARTIAL", opp.getUnderlying() + " " + err);
            }
            positionRepo.save(position);
        } catch (TimeoutException te) {
            position.setStatus("FAILED");
            position.setErrorMessage("Parallel place timeout 8s");
            positionRepo.save(position);
            addLog("EXEC", "TIMEOUT", opp.getUnderlying() + " " + opp.getStrike());
        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
        }
    }

    private static BrokerOrderRequest.Side flip(BrokerOrderRequest.Side s) {
        return s == BrokerOrderRequest.Side.BUY ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
    }

    private static String statusOf(BrokerOrderResponse r) {
        if (r == null) return "null";
        if (r.orderId() != null && !r.orderId().isBlank()) return "OK:" + r.orderId();
        return String.valueOf(r.status()) + ":" + r.message();
    }

    private BrokerOrderResponse place(BrokerAdapter adapter, String token, String symbol,
                                      BrokerOrderRequest.Side side, int qty) {
        BrokerOrderRequest req = BrokerOrderRequest.builder()
                .symbol(symbol)
                .exchange("NFO")
                .side(side)
                .quantity(qty)
                .price(0.0)
                .orderType(BrokerOrderRequest.OrderType.MARKET)
                .productType("NRML")
                .build();
        return adapter.placeOrder(token, req);
    }

    private boolean isPlaced(BrokerOrderResponse resp) {
        return resp != null && resp.orderId() != null && !resp.orderId().isBlank()
                && !"REJECTED".equalsIgnoreCase(resp.status())
                && !"ERROR".equalsIgnoreCase(resp.status());
    }

    private double estimateHedgedMargin(String underlying, int lots) {
        return HEDGED_MARGIN.getOrDefault(underlying, 200000.0) * 1.15 * Math.max(1, lots);
    }

    private String buildMonthlyFutSymbol(String underlying) {
        LocalDate monthly = optionChainService.getMonthlyExpiry(underlying);
        int yy = monthly.getYear() % 100;
        String mon = monthly.getMonth().name().substring(0, 3);
        return String.format("%s%02d%sFUT", underlying.replace(" ", ""), yy, mon);
    }

    private void addLog(String type, String status, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", System.currentTimeMillis());
        entry.put("time", LocalTime.now(ZoneId.of("Asia/Kolkata"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("type", type);
        entry.put("status", status);
        entry.put("message", message);
        execLogs.add(entry);
        if (execLogs.size() > 200) execLogs.remove(0);
        log.info("AutoExec [{}]/{}] {}", type, status, message);
    }
}
