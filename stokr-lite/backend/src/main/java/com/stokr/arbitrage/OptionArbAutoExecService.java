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

    /** Default refuse-to-trade floor if broker reports less free margin (₹). */
    private static final double DEFAULT_MIN_AVAILABLE_MARGIN = 5_000.0;
    /** Default keep this fraction of free margin unused as buffer. */
    private static final double DEFAULT_MARGIN_USAGE_CAP = 0.85;

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
        settings.put("availableMarginGate", DEFAULT_MIN_AVAILABLE_MARGIN);
        settings.put("marginUsageCap", DEFAULT_MARGIN_USAGE_CAP);
        settings.put("parallelTimeoutSec", 8);
        // Auto-exit when live PnL ≥ targetEdge − buffer (independent of entry auto-exec)
        settings.put("bidParityAutoExitEnabled", true);
        settings.put("bidParityExitNearBuffer", 10.0);
        // Dual track: paper 1-set always mirrors; live uses `enabled` + broker
        settings.put("paperAutoEnabled", true);
        settings.put("paperMaxOpen", 1);
        settings.put("smartRollEnabled", true);
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
        // Master switch: explicit camelCase `enabled` wins. Only fall back to legacy
        // bid_parity_auto_enabled when `enabled` was never loaded from DB.
        boolean hasExplicitEnabled = settings.containsKey("enabled") && settings.get("enabled") instanceof Boolean;
        if (!hasExplicitEnabled && settings.containsKey("bid_parity_auto_enabled")) {
            settings.put("enabled", parseBool(String.valueOf(settings.get("bid_parity_auto_enabled"))));
        }
        // Keep legacy mirror in sync for UI / older clients
        if (settings.get("enabled") instanceof Boolean) {
            boolean on = Boolean.TRUE.equals(settings.get("enabled"));
            settings.put("bid_parity_auto_enabled", on ? "1" : "0");
            settings.put("bid_parity_auto_enabled_bool", on);
            settings.put("auto_execute_enabled", on ? "true" : "false");
            settings.put("auto_execute_enabled_bool", on);
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
                || "scanner_minEdgeAfterCosts".equals(k)
                || "availableMarginGate".equals(k) || "marginUsageCap".equals(k)
                || "bidParityExitNearBuffer".equals(k) || "bid_parity_exit_near_buffer".equals(k)) {
            try { settings.put(k, Double.parseDouble(v)); } catch (Exception ignored) {}
        } else if (k.endsWith("Lots") || "maxOpenPositions".equals(k) || "max_total_positions".equals(k)
                || "max_positions_per_underlying".equals(k) || "parallelTimeoutSec".equals(k)
                || "paperMaxOpen".equals(k)) {
            try { settings.put(k, Integer.parseInt(v.replace(".0", ""))); } catch (Exception ignored) {}
        } else {
            settings.put(k, v);
        }
    }

    private double marginGate() {
        Object v = settings.get("availableMarginGate");
        if (v instanceof Number n) return Math.max(0, n.doubleValue());
        return DEFAULT_MIN_AVAILABLE_MARGIN;
    }

    private double marginUsageCap() {
        Object v = settings.get("marginUsageCap");
        if (v instanceof Number n) {
            double c = n.doubleValue();
            if (c > 1.0) c = c / 100.0; // allow "85" meaning 85%
            return Math.min(1.0, Math.max(0.1, c));
        }
        return DEFAULT_MARGIN_USAGE_CAP;
    }

    private int parallelTimeoutSec() {
        Object v = settings.get("parallelTimeoutSec");
        if (v instanceof Number n) return Math.min(30, Math.max(3, n.intValue()));
        return 8;
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
        out.put("availableMarginGate", marginGate());
        out.put("marginUsageCap", marginUsageCap());
        out.put("parallelTimeoutSec", parallelTimeoutSec());
        out.put("parallelLegs", true);
        out.put("qtyMode", "NAVIA_LOTS_OTHERS_UNITS");
        out.put("hedgedMarginEstimate", new LinkedHashMap<>(HEDGED_MARGIN));
        boolean autoExit = settings.get("bidParityAutoExitEnabled") instanceof Boolean
                ? Boolean.TRUE.equals(settings.get("bidParityAutoExitEnabled"))
                : parseBool(String.valueOf(settings.getOrDefault("bid_parity_auto_exit", "true")));
        out.put("bidParityAutoExitEnabled", autoExit);
        Object buf = settings.getOrDefault("bidParityExitNearBuffer",
                settings.getOrDefault("bid_parity_exit_near_buffer", 10.0));
        try {
            out.put("bidParityExitNearBuffer", Double.parseDouble(String.valueOf(buf)));
        } catch (Exception e) {
            out.put("bidParityExitNearBuffer", 10.0);
        }
        boolean paperAuto = settings.get("paperAutoEnabled") instanceof Boolean
                ? Boolean.TRUE.equals(settings.get("paperAutoEnabled"))
                : parseBool(String.valueOf(settings.getOrDefault("paperAutoEnabled", "true")));
        out.put("paperAutoEnabled", paperAuto);
        try {
            out.put("paperMaxOpen", Integer.parseInt(String.valueOf(
                    settings.getOrDefault("paperMaxOpen", 1)).replace(".0", "")));
        } catch (Exception e) {
            out.put("paperMaxOpen", 1);
        }
        boolean smartRoll = settings.get("smartRollEnabled") instanceof Boolean
                ? Boolean.TRUE.equals(settings.get("smartRollEnabled"))
                : parseBool(String.valueOf(settings.getOrDefault("smartRollEnabled", "true")));
        out.put("smartRollEnabled", smartRoll);
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

    /** Bulk update for Config UI — accepts camelCase keys only. */
    @Transactional
    public Map<String, Object> updateSettingsBulk(Map<String, Object> body) {
        if (body == null || body.isEmpty()) return getSettings();
        // Ignore accidental {key,value} wrapper bodies (would otherwise write literal "key"/"value" rows)
        if (body.containsKey("key") && body.containsKey("value") && body.size() <= 3) {
            updateSetting(String.valueOf(body.get("key")), String.valueOf(body.get("value")));
            return getSettings();
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            String key = e.getKey().trim();
            // Skip read-only / derived keys
            if (Set.of("parallelLegs", "qtyMode", "hedgedMarginEstimate",
                    "availableMarginGate_readonly", "key", "value").contains(key)) continue;
            if (key.endsWith("_bool") || key.contains(" ")) continue;
            updateSetting(key, String.valueOf(e.getValue()));
        }
        return getSettings();
    }

    /**
     * Live readiness probe for Bid Parity Config UI:
     * broker account present, TOTP re-login (Navia), AvailableMargin.
     */
    public Map<String, Object> probeBrokerReadiness() {
        Map<String, Object> out = new LinkedHashMap<>();
        String broker = String.valueOf(settings.getOrDefault("broker", "NAVIA")).toUpperCase(Locale.ROOT);
        out.put("broker", broker);
        out.put("autoExecEnabled", Boolean.TRUE.equals(settings.get("enabled")));
        out.put("parallelLegs", true);
        out.put("marginGate", marginGate());
        out.put("marginUsageCap", marginUsageCap());
        try {
            BrokerAccount account = resolveBrokerAccount(broker);
            if (account == null) {
                out.put("ok", false);
                out.put("connected", false);
                out.put("message", "No ACTIVE " + broker + " account. Connect " + broker + " under Brokers.");
                return out;
            }
            out.put("accountId", account.getId());
            out.put("clientId", account.getClientId());
            BrokerAdapter adapter = brokerService.getAdapter(broker);
            if ("NAVIA".equals(broker) && adapter instanceof NaviaAdapter navia) {
                String fresh = navia.loginWithTotp(account);
                account.setAccessToken(fresh);
                brokerAccountRepo.save(account);
                out.put("login", "OK");
            } else {
                out.put("login", "SKIPPED");
            }
            BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
            double avail = margin != null ? margin.doubleValue() : -1;
            out.put("availableMargin", avail);
            out.put("connected", avail >= 0);
            boolean gateOk = avail >= marginGate();
            out.put("marginGateOk", gateOk);
            out.put("ok", gateOk);
            out.put("message", gateOk
                    ? broker + " connected. AvailableMargin ₹" + String.format("%.0f", avail)
                    : broker + " connected but AvailableMargin ₹" + String.format("%.0f", avail)
                      + " below gate ₹" + String.format("%.0f", marginGate()));
            // Estimate how many NIFTY sets fit
            double usable = avail * marginUsageCap();
            double oneSet = estimateHedgedMargin("NIFTY", 1);
            out.put("usableMargin", usable);
            out.put("niftyOneSetEstimate", oneSet);
            out.put("maxNiftySets", oneSet > 0 ? (int) Math.floor(usable / oneSet) : 0);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("connected", false);
            out.put("message", broker + " probe failed: " + e.getMessage());
        }
        return out;
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
        long currentOpen = countLiveOpenPositions();
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
        double minMargin = marginGate();
        double usageCap = marginUsageCap();
        if (availableMargin < minMargin) {
            addLog("MARGIN", "BLOCKED", "AvailableMargin ₹" + String.format("%.0f", availableMargin)
                    + " < minimum ₹" + String.format("%.0f", minMargin) + " — no trades");
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
            // Hard ceiling: post Black-76, real 1-lot edges are small. Legacy stock-parity
            // phantoms were ₹2–4k — never auto-fire those.
            double maxEdge = ((Number) settings.getOrDefault(key + "MaxEdge",
                    settings.getOrDefault("max_edge_after_costs", 800.0))).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() > maxEdge) {
                addLog("SIGNAL", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " edge ₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                        + " > max ₹" + String.format("%.0f", maxEdge) + " (likely inflated model)");
                continue;
            }
            if (opp.getEdgePoints() != null && opp.getEdgePoints().doubleValue() > 25.0) {
                addLog("SIGNAL", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " edgePts " + opp.getEdgePoints() + " > 25 (stale/inflated)");
                continue;
            }
            if (opp.getExpiryDate() == null) continue;

            String action = normalizeAction(opp.getAction());
            if (action == null) {
                addLog("SIGNAL", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " unsupported action: " + opp.getAction());
                continue;
            }

            if (positionRepo.findActiveByFingerprint(
                    opp.getUnderlying(), opp.getStrike(), action, "BID").stream().findFirst().isPresent()
                    || positionRepo.findByOpportunityIdOrderByEnteredAtDesc(opp.getId()).stream()
                    .anyMatch(p -> {
                        String s = p.getStatus() != null ? p.getStatus().toUpperCase(Locale.ROOT) : "";
                        return "OPEN".equals(s) || "ENTERED".equals(s) || "PARTIAL".equals(s) || "EXECUTING".equals(s);
                    })) {
                continue;
            }

            // Fresh AvailableMargin (no stale cache) + required check before placing 3 legs
            double liveMargin = fetchAvailableMarginOrAbort(adapter, account);
            if (liveMargin < 0) return;
            availableMargin = liveMargin;
            if (availableMargin < minMargin) {
                addLog("MARGIN", "BLOCKED", "Live AvailableMargin ₹" + String.format("%.0f", availableMargin)
                        + " below floor — stopping cycle");
                return;
            }

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            double required = estimateHedgedMargin(opp.getUnderlying(), lots);

            // Navia live pre-flight: GetOrderMargin on monthly FUT @ order qty (lots).
            // Naked FUT required is an upper bound; hedged 3-leg is lower — take
            // max(staticHedgedEstimate, liveNakedFutRequired * 0.55) as conservative required.
            if (adapter instanceof NaviaAdapter navia) {
                try {
                    String futSymbol = buildMonthlyFutSymbol(opp.getUnderlying());
                    String futSide = "CONVERSION".equals(action) ? "Sell" : "Buy";
                    int orderQty = Math.max(1, lots); // Navia F&O qty = lots
                    NaviaAdapter.MarginProbe probe = navia.getOrderMarginProbe(
                            account.getAccessToken(), futSymbol, orderQty, futSide, "NRML");
                    if (probe.available() != null && probe.available().doubleValue() >= 0) {
                        availableMargin = probe.available().doubleValue();
                        marginCacheValue = availableMargin;
                        marginCacheAt.set(System.currentTimeMillis());
                    }
                    if (probe.required() != null && probe.required().doubleValue() > 0) {
                        double liveReq = probe.required().doubleValue() * 0.55;
                        required = Math.max(required, liveReq);
                        addLog("MARGIN", "NAVIA_PROBE", futSymbol + " qty=" + orderQty
                                + " " + futSide
                                + " Available₹" + String.format("%.0f", availableMargin)
                                + " NakedReq₹" + String.format("%.0f", probe.required().doubleValue())
                                + " HedgedReq₹" + String.format("%.0f", required));
                    } else {
                        addLog("MARGIN", "NAVIA_PROBE", futSymbol + " qty=" + orderQty
                                + " Available₹" + String.format("%.0f", availableMargin)
                                + " Required=n/a → using estimate ₹" + String.format("%.0f", required));
                    }
                } catch (Exception e) {
                    addLog("MARGIN", "BLOCKED", "Navia live margin probe failed — abort fire: " + e.getMessage());
                    return;
                }
            }

            double usable = availableMargin * usageCap;
            addLog("MARGIN", "CHECK", opp.getUnderlying() + " " + opp.getStrike()
                    + " Available₹" + String.format("%.0f", availableMargin)
                    + " Required₹" + String.format("%.0f", required)
                    + " Usable₹" + String.format("%.0f", usable)
                    + " (cap " + String.format("%.0f", usageCap * 100) + "%)");

            if (required > usable) {
                addLog("MARGIN", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " needs ₹" + String.format("%.0f", required)
                        + " but usable ₹" + String.format("%.0f", usable)
                        + " (avail ₹" + String.format("%.0f", availableMargin) + " × "
                        + String.format("%.0f", usageCap * 100) + "%) — 3-leg NOT placed");
                continue;
            }

            addLog("SIGNAL", "FIRING", opp.getUnderlying() + " " + opp.getStrike()
                    + " " + action + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " ≥ ₹" + String.format("%.0f", minEdge)
                    + " | avail ₹" + String.format("%.0f", availableMargin)
                    + " req ₹" + String.format("%.0f", required));
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
    public static String normalizeAction(String raw) {
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
                .futEntryPrice(opp.getFuturesPrice())
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
        int timeoutSec = parallelTimeoutSec();
        addLog("EXEC", "QTY", opp.getUnderlying() + " broker=" + adapter.getBrokerName()
                + " lots=" + lots + " lotSize=" + lotSize + " orderQty=" + qty
                + (naviaLots ? " (Navia lots)" : " (units)")
                + " parallelTimeout=" + timeoutSec + "s");

        BrokerOrderRequest.Side ceSide = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;
        BrokerOrderRequest.Side peSide = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
        BrokerOrderRequest.Side futSide = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;

        CompletableFuture<BrokerOrderResponse> ceF = CompletableFuture.supplyAsync(
                () -> place(adapter, token, ceSymbol, ceSide, qty), execPool);
        CompletableFuture<BrokerOrderResponse> peF = CompletableFuture.supplyAsync(
                () -> place(adapter, token, peSymbol, peSide, qty), execPool);
        CompletableFuture<BrokerOrderResponse> futF = CompletableFuture.supplyAsync(
                () -> place(adapter, token, futSymbol, futSide, qty), execPool);

        try {
            CompletableFuture.allOf(ceF, peF, futF).get(timeoutSec, TimeUnit.SECONDS);
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
            unwindFilled(adapter, token, ceSymbol, peSymbol, futSymbol, ceSide, peSide, futSide,
                    qty, ceOk, peOk, futOk, timeoutSec);

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
            boolean ceOk = isPlaced(ceF.getNow(null));
            boolean peOk = isPlaced(peF.getNow(null));
            boolean futOk = isPlaced(futF.getNow(null));
            if (ceOk && ceF.getNow(null) != null) position.setCeOrderId(ceF.getNow(null).orderId());
            if (peOk && peF.getNow(null) != null) position.setPeOrderId(peF.getNow(null).orderId());
            if (futOk && futF.getNow(null) != null) position.setFutOrderId(futF.getNow(null).orderId());
            if (ceOk || peOk || futOk) {
                unwindFilled(adapter, token, ceSymbol, peSymbol, futSymbol, ceSide, peSide, futSide,
                        qty, ceOk, peOk, futOk, timeoutSec);
                position.setStatus("PARTIAL");
                position.setErrorMessage("Parallel place timeout " + timeoutSec + "s (unwind attempted)");
                addLog("EXEC", "TIMEOUT", opp.getUnderlying() + " " + opp.getStrike()
                        + " partial fills unwound CE=" + ceOk + " PE=" + peOk + " FUT=" + futOk);
            } else {
                position.setStatus("FAILED");
                position.setErrorMessage("Parallel place timeout " + timeoutSec + "s — no confirmed fills yet; check order book");
                addLog("EXEC", "TIMEOUT", opp.getUnderlying() + " " + opp.getStrike()
                        + " — no confirmed fills at timeout; verify Navia order book");
            }
            positionRepo.save(position);
        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
        }
    }

    private void unwindFilled(BrokerAdapter adapter, String token,
                              String ceSymbol, String peSymbol, String futSymbol,
                              BrokerOrderRequest.Side ceSide, BrokerOrderRequest.Side peSide,
                              BrokerOrderRequest.Side futSide, int qty,
                              boolean ceOk, boolean peOk, boolean futOk, int timeoutSec) {
        List<CompletableFuture<BrokerOrderResponse>> unwind = new ArrayList<>();
        if (ceOk) unwind.add(CompletableFuture.supplyAsync(
                () -> place(adapter, token, ceSymbol, flip(ceSide), qty), execPool));
        if (peOk) unwind.add(CompletableFuture.supplyAsync(
                () -> place(adapter, token, peSymbol, flip(peSide), qty), execPool));
        if (futOk) unwind.add(CompletableFuture.supplyAsync(
                () -> place(adapter, token, futSymbol, flip(futSide), qty), execPool));
        if (unwind.isEmpty()) return;
        try {
            CompletableFuture.allOf(unwind.toArray(CompletableFuture[]::new)).get(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception unwindEx) {
            addLog("EXEC", "UNWIND_ERR", unwindEx.getMessage());
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

    /** Count non-paper active Bid Parity / live positions. */
    public long countLiveOpenPositions() {
        return positionRepo.findAllActive().stream()
                .filter(p -> !isPaperPosition(p))
                .count();
    }

    public long countPaperOpenPositions() {
        return positionRepo.findAllActive().stream()
                .filter(OptionArbAutoExecService::isPaperPosition)
                .count();
    }

    public boolean isPaperAutoEnabled() {
        applyLegacyAliases();
        if (settings.get("paperAutoEnabled") instanceof Boolean b) return b;
        return parseBool(String.valueOf(settings.getOrDefault("paperAutoEnabled", "true")));
    }

    public boolean isSmartRollEnabled() {
        applyLegacyAliases();
        if (settings.get("smartRollEnabled") instanceof Boolean b) return b;
        return parseBool(String.valueOf(settings.getOrDefault("smartRollEnabled", "true")));
    }

    public int paperMaxOpen() {
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(settings.getOrDefault("paperMaxOpen", 1)).replace(".0", "")));
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Paper track: enter up to paperMaxOpen virtual 1-lot sets on NIFTY/BN edges.
     * Runs independently of live broker track.
     */
    public void evaluateAndExecutePaper(List<OptionArbOpportunity> newOpps) {
        applyLegacyAliases();
        if (!isPaperAutoEnabled()) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 16)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        long open = countPaperOpenPositions();
        int max = paperMaxOpen();
        if (open >= max) {
            addLog("PAPER", "SKIP", "Paper max open reached: " + open);
            return;
        }

        List<OptionArbOpportunity> ranked = newOpps.stream()
                .filter(o -> o.getUnderlying() != null && o.getEdgeAfterCosts() != null && o.getStrike() != null)
                .filter(o -> "NIFTY".equalsIgnoreCase(o.getUnderlying()) || "BANKNIFTY".equalsIgnoreCase(o.getUnderlying()))
                .sorted((a, b) -> Double.compare(
                        b.getEdgeAfterCosts().doubleValue(),
                        a.getEdgeAfterCosts().doubleValue()))
                .toList();

        for (OptionArbOpportunity opp : ranked) {
            if (open >= max) break;
            String key = opp.getUnderlying().toLowerCase(Locale.ROOT);
            if (!Boolean.TRUE.equals(settings.get(key + "Enabled"))) continue;

            String stratType = opp.getStrategyType() != null ? opp.getStrategyType().toUpperCase(Locale.ROOT) : "";
            if (!stratType.contains("PARITY") && !stratType.contains("BID")) continue;

            double minEdge = ((Number) settings.getOrDefault(key + "MinEdge", 300.0)).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() < minEdge) continue;
            double maxEdge = ((Number) settings.getOrDefault(key + "MaxEdge",
                    settings.getOrDefault("max_edge_after_costs", 800.0))).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() > maxEdge) continue;
            if (opp.getEdgePoints() != null && opp.getEdgePoints().doubleValue() > 25.0) continue;
            if (opp.getExpiryDate() == null) continue;

            String action = normalizeAction(opp.getAction());
            if (action == null) continue;

            boolean already = positionRepo.findActiveByFingerprint(
                    opp.getUnderlying(), opp.getStrike(), action, "BID").stream()
                    .anyMatch(OptionArbAutoExecService::isPaperPosition);
            if (already) continue;

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            LivePosition pos = enterPaperPosition(opp, lots, action);
            if (pos != null) {
                open++;
                addLog("PAPER", "ENTERED", opp.getUnderlying() + " " + opp.getStrike()
                        + " " + action + " edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                        + " pos#" + pos.getId());
            }
        }
    }

    public LivePosition enterPaperPosition(OptionArbOpportunity opp, int lots, String action) {
        boolean conversion = "CONVERSION".equals(action);
        int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
        LocalDate expiry = opp.getExpiryDate();
        String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), expiry, opp.getStrike(), "CE");
        String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), expiry, opp.getStrike(), "PE");
        String futSymbol = buildMonthlyFutSymbol(opp.getUnderlying());
        String paperId = "PAPER-" + System.currentTimeMillis();

        BigDecimal ce = conversion
                ? (opp.getCeAsk() != null ? opp.getCeAsk() : opp.getCeEntryPrice())
                : (opp.getCeBid() != null ? opp.getCeBid() : opp.getCeEntryPrice());
        BigDecimal pe = conversion
                ? (opp.getPeBid() != null ? opp.getPeBid() : opp.getPeEntryPrice())
                : (opp.getPeAsk() != null ? opp.getPeAsk() : opp.getPeEntryPrice());
        BigDecimal fut = opp.getFuturesPrice();

        String legs = conversion
                ? String.format("BUY %d CE @ %.1f | SELL %d PE @ %.1f | SELL %s FUT @ %.1f",
                opp.getStrike(), ce != null ? ce.doubleValue() : 0,
                opp.getStrike(), pe != null ? pe.doubleValue() : 0,
                opp.getUnderlying(), fut != null ? fut.doubleValue() : 0)
                : String.format("SELL %d CE @ %.1f | BUY %d PE @ %.1f | BUY %s FUT @ %.1f",
                opp.getStrike(), ce != null ? ce.doubleValue() : 0,
                opp.getStrike(), pe != null ? pe.doubleValue() : 0,
                opp.getUnderlying(), fut != null ? fut.doubleValue() : 0);

        LivePosition position = LivePosition.builder()
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(action)
                .strategyType(opp.getStrategyType() != null ? opp.getStrategyType() : "BID_PARITY")
                .ceSymbol(ceSymbol)
                .peSymbol(peSymbol)
                .futSymbol(futSymbol)
                .lots(Math.max(1, lots))
                .lotSize(lotSize)
                .ceEntryPrice(ce)
                .peEntryPrice(pe)
                .futEntryPrice(fut)
                .targetEdge(opp.getEdgeAfterCosts())
                .currentPnl(BigDecimal.ZERO)
                .status("ENTERED")
                .ceOrderId(paperId)
                .peOrderId(paperId)
                .futOrderId(paperId)
                .errorMessage("PAPER BID_PARITY · " + legs)
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        return positionRepo.save(position);
    }

    /**
     * Smart roll: close CE+PE only, keep FUT, open new CE+PE on candidate strike.
     * Returns new/updated position on success, null on failure (caller may full-exit).
     */
    public LivePosition smartRoll(LivePosition pos, OptionArbOpportunity newOpp, double capturedPnl) {
        if (pos == null || newOpp == null || newOpp.getStrike() == null) return null;
        if (Objects.equals(pos.getStrike(), newOpp.getStrike())) return null;

        String action = normalizeAction(pos.getAction());
        if (action == null) action = normalizeAction(newOpp.getAction());
        if (action == null) return null;
        boolean conversion = "CONVERSION".equals(action);
        boolean paper = isPaperPosition(pos);

        addLog("ROLL", "START", "pos#" + pos.getId() + " " + pos.getUnderlying()
                + " " + pos.getStrike() + " → " + newOpp.getStrike()
                + " captured≈₹" + String.format("%.0f", capturedPnl)
                + (paper ? " PAPER" : " LIVE"));

        if (paper) {
            return smartRollPaper(pos, newOpp, action, conversion, capturedPnl);
        }
        return smartRollLive(pos, newOpp, action, conversion, capturedPnl);
    }

    private LivePosition smartRollPaper(LivePosition pos, OptionArbOpportunity newOpp,
                                        String action, boolean conversion, double capturedPnl) {
        LocalDate expiry = newOpp.getExpiryDate() != null ? newOpp.getExpiryDate()
                : (pos.getOpportunityId() != null ? null : null);
        if (newOpp.getExpiryDate() == null) {
            addLog("ROLL", "ERROR", "pos#" + pos.getId() + " new opp missing expiry");
            return null;
        }
        expiry = newOpp.getExpiryDate();
        String ceSymbol = optionChainService.buildNfoSymbol(pos.getUnderlying(), expiry, newOpp.getStrike(), "CE");
        String peSymbol = optionChainService.buildNfoSymbol(pos.getUnderlying(), expiry, newOpp.getStrike(), "PE");
        BigDecimal ce = conversion
                ? (newOpp.getCeAsk() != null ? newOpp.getCeAsk() : newOpp.getCeEntryPrice())
                : (newOpp.getCeBid() != null ? newOpp.getCeBid() : newOpp.getCeEntryPrice());
        BigDecimal pe = conversion
                ? (newOpp.getPeBid() != null ? newOpp.getPeBid() : newOpp.getPeEntryPrice())
                : (newOpp.getPeAsk() != null ? newOpp.getPeAsk() : newOpp.getPeEntryPrice());

        String prev = pos.getErrorMessage() != null ? pos.getErrorMessage() : "";
        pos.setErrorMessage(prev + " | ROLL " + pos.getStrike() + "→" + newOpp.getStrike()
                + " captured₹" + Math.round(capturedPnl));
        // Realize captured PnL into currentPnl baseline for next leg
        pos.setCurrentPnl(BigDecimal.valueOf(Math.round(capturedPnl * 100.0) / 100.0));
        pos.setStrike(newOpp.getStrike());
        pos.setOpportunityId(newOpp.getId());
        pos.setCeSymbol(ceSymbol);
        pos.setPeSymbol(peSymbol);
        pos.setCeEntryPrice(ce);
        pos.setPeEntryPrice(pe);
        // Keep futEntryPrice + futOrderId
        pos.setTargetEdge(newOpp.getEdgeAfterCosts());
        pos.setAction(action);
        pos.setStatus("ENTERED");
        pos.setCeOrderId("PAPER-" + System.currentTimeMillis());
        pos.setPeOrderId(pos.getCeOrderId());
        LivePosition saved = positionRepo.save(pos);
        addLog("ROLL", "OK", "PAPER pos#" + saved.getId() + " now strike=" + saved.getStrike()
                + " target=₹" + saved.getTargetEdge());
        return saved;
    }

    private LivePosition smartRollLive(LivePosition pos, OptionArbOpportunity newOpp,
                                       String action, boolean conversion, double capturedPnl) {
        applyLegacyAliases();
        String broker = String.valueOf(settings.getOrDefault("broker", "NAVIA")).toUpperCase(Locale.ROOT);
        if ("PAPER".equals(broker)) {
            addLog("ROLL", "ERROR", "Live roll but broker=PAPER");
            return null;
        }
        try {
            BrokerAccount account = resolveBrokerAccount(broker);
            if (account == null) {
                addLog("ROLL", "ERROR", "No ACTIVE " + broker);
                return falseReturn();
            }
            BrokerAdapter adapter = brokerService.getAdapter(broker);
            if ("NAVIA".equals(broker) && adapter instanceof NaviaAdapter navia) {
                String fresh = navia.loginWithTotp(account);
                account.setAccessToken(fresh);
                brokerAccountRepo.save(account);
            }

            String ceSymbol = pos.getCeSymbol();
            String peSymbol = pos.getPeSymbol();
            if (ceSymbol == null || peSymbol == null) {
                addLog("ROLL", "ERROR", "pos#" + pos.getId() + " missing option symbols");
                return null;
            }
            if (newOpp.getExpiryDate() == null) return null;

            int lots = pos.getLots() != null ? Math.max(1, pos.getLots()) : 1;
            int lotSize = pos.getLotSize() != null ? pos.getLotSize()
                    : OptionChainService.getLotSize(pos.getUnderlying());
            boolean naviaLots = adapter instanceof NaviaAdapter
                    || "NAVIA".equalsIgnoreCase(adapter.getBrokerName());
            int qty = naviaLots ? lots : lots * lotSize;
            String token = account.getAccessToken();
            int timeoutSec = parallelTimeoutSec();

            // 1) Close option legs only (reverse of entry)
            BrokerOrderRequest.Side closeCe = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
            BrokerOrderRequest.Side closePe = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;
            CompletableFuture<BrokerOrderResponse> cCe = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, ceSymbol, closeCe, qty), execPool);
            CompletableFuture<BrokerOrderResponse> cPe = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, peSymbol, closePe, qty), execPool);
            CompletableFuture.allOf(cCe, cPe).get(timeoutSec, TimeUnit.SECONDS);
            boolean ceClosed = isPlaced(cCe.getNow(null));
            boolean peClosed = isPlaced(cPe.getNow(null));
            if (!ceClosed || !peClosed) {
                addLog("ROLL", "ERROR", "pos#" + pos.getId() + " option close failed CE="
                        + statusOf(cCe.getNow(null)) + " PE=" + statusOf(cPe.getNow(null)));
                return null;
            }

            // 2) Open new option legs (fut stays)
            String newCe = optionChainService.buildNfoSymbol(pos.getUnderlying(), newOpp.getExpiryDate(), newOpp.getStrike(), "CE");
            String newPe = optionChainService.buildNfoSymbol(pos.getUnderlying(), newOpp.getExpiryDate(), newOpp.getStrike(), "PE");
            BrokerOrderRequest.Side openCe = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;
            BrokerOrderRequest.Side openPe = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
            CompletableFuture<BrokerOrderResponse> oCe = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, newCe, openCe, qty), execPool);
            CompletableFuture<BrokerOrderResponse> oPe = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, newPe, openPe, qty), execPool);
            CompletableFuture.allOf(oCe, oPe).get(timeoutSec, TimeUnit.SECONDS);
            boolean ceOpen = isPlaced(oCe.getNow(null));
            boolean peOpen = isPlaced(oPe.getNow(null));
            if (!ceOpen || !peOpen) {
                addLog("ROLL", "ERROR", "pos#" + pos.getId() + " new options failed — flattening fut");
                // Emergency: close fut to avoid naked hedge
                String futSymbol = pos.getFutSymbol() != null ? pos.getFutSymbol()
                        : buildMonthlyFutSymbol(pos.getUnderlying());
                BrokerOrderRequest.Side futClose = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;
                place(adapter, token, futSymbol, futClose, qty);
                return null;
            }

            String prev = pos.getErrorMessage() != null ? pos.getErrorMessage() : "";
            pos.setErrorMessage(prev + " | ROLL " + pos.getStrike() + "→" + newOpp.getStrike()
                    + " captured₹" + Math.round(capturedPnl)
                    + " CE:" + oCe.getNow(null).orderId() + " PE:" + oPe.getNow(null).orderId());
            pos.setCurrentPnl(BigDecimal.valueOf(Math.round(capturedPnl * 100.0) / 100.0));
            pos.setStrike(newOpp.getStrike());
            pos.setOpportunityId(newOpp.getId());
            pos.setCeSymbol(newCe);
            pos.setPeSymbol(newPe);
            pos.setCeEntryPrice(newOpp.getCeAsk() != null ? newOpp.getCeAsk() : newOpp.getCeEntryPrice());
            pos.setPeEntryPrice(newOpp.getPeBid() != null ? newOpp.getPeBid() : newOpp.getPeEntryPrice());
            pos.setTargetEdge(newOpp.getEdgeAfterCosts());
            pos.setCeOrderId(oCe.getNow(null).orderId());
            pos.setPeOrderId(oPe.getNow(null).orderId());
            pos.setStatus("OPEN");
            LivePosition saved = positionRepo.save(pos);
            addLog("ROLL", "OK", "LIVE pos#" + saved.getId() + " now strike=" + saved.getStrike()
                    + " target=₹" + saved.getTargetEdge() + " (fut held)");
            return saved;
        } catch (Exception e) {
            addLog("ROLL", "ERROR", "pos#" + pos.getId() + " " + e.getMessage());
            log.error("smartRollLive failed: {}", e.getMessage());
            return null;
        }
    }

    private LivePosition falseReturn() { return null; }

    private static boolean isPaperPosition(LivePosition pos) {
        if (pos == null) return true;
        String id = pos.getCeOrderId() != null ? pos.getCeOrderId() : "";
        if (id.startsWith("PAPER")) return true;
        String msg = pos.getErrorMessage() != null ? pos.getErrorMessage().toUpperCase(Locale.ROOT) : "";
        return msg.contains("PAPER");
    }

    /**
     * Close a live 3-leg hedge at market (reverse CE/PE/FUT).
     * No-op for paper positions. Returns true if broker close attempted successfully
     * or position is paper (caller should still mark EXITED in trade book).
     */
    public boolean closeLiveHedge(LivePosition pos) {
        if (pos == null) return false;
        if (isPaperPosition(pos)) {
            addLog("EXIT", "PAPER", "pos#" + pos.getId() + " virtual close (no broker orders)");
            return true;
        }
        applyLegacyAliases();
        String broker = String.valueOf(settings.getOrDefault("broker", "NAVIA")).toUpperCase(Locale.ROOT);
        try {
            BrokerAccount account = resolveBrokerAccount(broker);
            if (account == null) {
                addLog("EXIT", "ERROR", "pos#" + pos.getId() + " no ACTIVE " + broker + " account");
                return false;
            }
            BrokerAdapter adapter = brokerService.getAdapter(broker);
            if ("NAVIA".equals(broker) && adapter instanceof NaviaAdapter navia) {
                String fresh = navia.loginWithTotp(account);
                account.setAccessToken(fresh);
                brokerAccountRepo.save(account);
            }
            String action = normalizeAction(pos.getAction());
            if (action == null) action = "CONVERSION";
            boolean conversion = "CONVERSION".equals(action);

            String ceSymbol = pos.getCeSymbol();
            String peSymbol = pos.getPeSymbol();
            String futSymbolRaw = pos.getFutSymbol();
            final String futSymbol = (futSymbolRaw == null || futSymbolRaw.isBlank())
                    ? buildMonthlyFutSymbol(pos.getUnderlying())
                    : futSymbolRaw;
            if (ceSymbol == null || peSymbol == null) {
                addLog("EXIT", "ERROR", "pos#" + pos.getId() + " missing option symbols");
                return false;
            }

            int lots = pos.getLots() != null ? Math.max(1, pos.getLots()) : 1;
            int lotSize = pos.getLotSize() != null ? pos.getLotSize()
                    : OptionChainService.getLotSize(pos.getUnderlying());
            boolean naviaLots = adapter instanceof NaviaAdapter
                    || "NAVIA".equalsIgnoreCase(adapter.getBrokerName());
            int qty = naviaLots ? lots : lots * lotSize;
            String token = account.getAccessToken();
            int timeoutSec = parallelTimeoutSec();

            // Reverse entry sides
            BrokerOrderRequest.Side ceSide = conversion ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
            BrokerOrderRequest.Side peSide = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;
            BrokerOrderRequest.Side futSide = conversion ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL;

            addLog("EXIT", "FIRING", "pos#" + pos.getId() + " " + pos.getUnderlying() + " "
                    + pos.getStrike() + " " + action + " CLOSE 3-leg qty=" + qty);

            CompletableFuture<BrokerOrderResponse> ceF = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, ceSymbol, ceSide, qty), execPool);
            CompletableFuture<BrokerOrderResponse> peF = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, peSymbol, peSide, qty), execPool);
            CompletableFuture<BrokerOrderResponse> futF = CompletableFuture.supplyAsync(
                    () -> place(adapter, token, futSymbol, futSide, qty), execPool);

            CompletableFuture.allOf(ceF, peF, futF).get(timeoutSec, TimeUnit.SECONDS);
            boolean ceOk = isPlaced(ceF.getNow(null));
            boolean peOk = isPlaced(peF.getNow(null));
            boolean futOk = isPlaced(futF.getNow(null));
            addLog("EXIT", (ceOk && peOk && futOk) ? "OK" : "PARTIAL",
                    "pos#" + pos.getId()
                            + " CE:" + statusOf(ceF.getNow(null))
                            + " PE:" + statusOf(peF.getNow(null))
                            + " FUT:" + statusOf(futF.getNow(null)));
            return ceOk && peOk && futOk;
        } catch (Exception e) {
            addLog("EXIT", "ERROR", "pos#" + pos.getId() + " " + e.getMessage());
            log.error("closeLiveHedge failed pos#{}: {}", pos.getId(), e.getMessage());
            return false;
        }
    }

    void addLog(String type, String status, String message) {
        addLogInternal(type, status, message);
    }

    private void addLogInternal(String type, String status, String message) {
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
