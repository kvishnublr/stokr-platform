package com.stokr.arbitrage;

import com.stokr.broker.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionArbAutoExecService {

    private final OptionArbOpportunityRepository oppRepo;
    private final LivePositionRepository positionRepo;
    private final BrokerService brokerService;
    private final BrokerAccountRepository brokerAccountRepo;
    private final OptionChainService optionChainService;

    private final ConcurrentHashMap<String, Map<String, Object>> autoExecSettings = new ConcurrentHashMap<>();
    private final AutoExecSettingRepository autoExecSettingRepo;

    private final List<Map<String, Object>> execLogs = Collections.synchronizedList(new ArrayList<>());

    /** Settings-key prefixes for each of the 6 real auto-executing strategies. */
    private static final List<String> STRATEGY_PREFIXES =
        List.of("bidParity", "box", "vertical", "butterfly", "condor", "ironCondor");

    private static String strategyPrefix(String strategyType) {
        String s = strategyType == null ? "" : strategyType.toUpperCase();
        if (s.contains("BID")) return "bidParity";
        if (s.contains("BOX")) return "box";
        if (s.contains("VERTICAL")) return "vertical";
        if (s.contains("BUTTERFLY")) return "butterfly";
        if (s.contains("IRON")) return "ironCondor";
        if (s.contains("CONDOR")) return "condor";
        return "bidParity";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    @PostConstruct
    public void init() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("enabled", true);
        defaults.put("broker", "NAVIA");
        // Per-strategy, per-underlying entry thresholds -- each of the 6 real auto-executing
        // strategies (Bid Parity/Box/Vertical/Butterfly/Condor/Iron Condor) gets its own
        // enabled/minEdge/lots per symbol, e.g. "boxNiftyMinEdge" vs "bidParityNiftyMinEdge".
        // Previously these were ONE shared set of thresholds (niftyEnabled/niftyMinEdge/
        // niftyLots) gated only by a coarse ALL/PARITY/BOX filter, so e.g. Box and Bid Parity
        // couldn't have different edge requirements, and Vertical/Butterfly/Condor/Iron Condor
        // had no dedicated control at all (they rode on "ALL").
        for (String prefix : STRATEGY_PREFIXES) {
            for (String u : List.of("Nifty", "Banknifty", "Finnifty", "Midcpnifty")) {
                defaults.put(prefix + u + "Enabled", true);
                defaults.put(prefix + u + "MinEdge", 800.0);
                defaults.put(prefix + u + "Lots", 1);
            }
        }
        defaults.put("maxOpenPositions", 1);
        defaults.put("maxDailyLoss", 5000.0);
        defaults.put("stopLossEnabled", true);
        defaults.put("stopLossPct", 50.0);
        defaults.put("rolloverEnabled", true);
        defaults.put("rolloverThresholdPct", 90.0);
        defaults.put("autoExitEnabled", true);
        defaults.put("autoExitThresholdPct", 90.0);
        // Auto-roll: if a Butterfly position sits outside its profit zone continuously for
        // <symbol>AutoRollBreachMinutes, close it automatically and propose a re-centered
        // replacement (which still requires a one-click confirm before it's actually entered --
        // see AutoRollService). Per-underlying, same pattern as the Auto-Execute Engine cards
        // above. Off by default -- this is a new, higher-risk automation.
        for (String u : List.of("nifty", "banknifty", "finnifty", "midcpnifty")) {
            defaults.put(u + "AutoRollEnabled", false);
            defaults.put(u + "AutoRollBreachMinutes", 5);
            defaults.put(u + "AutoRollMaxRolls", 2);
        }

        // Map defaults to PAPER and LIVE namespaces
        autoExecSettings.put("PAPER", new ConcurrentHashMap<>(defaults));
        autoExecSettings.put("LIVE", new ConcurrentHashMap<>(defaults));
        autoExecSettings.put("global", new ConcurrentHashMap<>(defaults));

        // Load persisted settings from DB
        try {
            List<AutoExecSetting> dbSettings = autoExecSettingRepo.findAll();
            for (AutoExecSetting s : dbSettings) {
                String fullKey = s.getSettingKey();
                String val = s.getSettingValue();
                
                String profileKey = "PAPER";
                String key = fullKey;
                if (fullKey.startsWith("PAPER_")) { profileKey = "PAPER"; key = fullKey.substring(6); }
                else if (fullKey.startsWith("LIVE_")) { profileKey = "LIVE"; key = fullKey.substring(5); }
                else continue; // Ignore old global settings
                
                Map<String, Object> targetMap = autoExecSettings.get(profileKey);
                
                if ("enabled".equals(key)) targetMap.put(key, Boolean.parseBoolean(val));
                else if (key.endsWith("Enabled")) targetMap.put(key, Boolean.parseBoolean(val));
                else if (key.endsWith("MinEdge") || key.equals("maxDailyLoss") || key.equals("rolloverThresholdPct") || key.equals("autoExitThresholdPct"))
                    targetMap.put(key, Double.parseDouble(val));
                else if (key.endsWith("Lots") || key.equals("maxOpenPositions") || key.endsWith("AutoRollBreachMinutes") || key.endsWith("AutoRollMaxRolls"))
                    targetMap.put(key, Integer.parseInt(val));
                else targetMap.put(key, val);
            }
            log.info("Loaded {} auto-exec settings from DB", dbSettings.size());
        } catch (Exception e) {
            log.debug("Failed to load auto-exec settings from DB: {}", e.getMessage());
        }
    }

    public Map<String, Object> getSettings() {
        return getSettings("PAPER");
    }
    public Map<String, Object> getSettings(String mode) {
        String key = "LIVE".equalsIgnoreCase(mode) ? "LIVE" : "PAPER";
        return new LinkedHashMap<>(autoExecSettings.getOrDefault(key, Map.of()));
    }

    public void updateSetting(String mode, String key, String value) {
        String profileKey = "LIVE".equalsIgnoreCase(mode) ? "LIVE" : "PAPER";
        Map<String, Object> settings = autoExecSettings.computeIfAbsent(profileKey, k -> new ConcurrentHashMap<>());
        
        if ("enabled".equals(key)) settings.put("enabled", Boolean.parseBoolean(value));
        else if (key.endsWith("Enabled")) settings.put(key, Boolean.parseBoolean(value));
        else if (key.endsWith("MinEdge") || key.equals("maxDailyLoss") || key.equals("rolloverThresholdPct") || key.equals("autoExitThresholdPct")) settings.put(key, Double.parseDouble(value));
        else if (key.endsWith("Lots") || key.equals("maxOpenPositions") || key.endsWith("AutoRollBreachMinutes") || key.endsWith("AutoRollMaxRolls")) settings.put(key, Integer.parseInt(value));
        else settings.put(key, value);

        // Persist to DB
        try {
            String dbKey = profileKey + "_" + key;
            AutoExecSetting dbSetting = autoExecSettingRepo.findBySettingKey(dbKey)
                    .orElse(new AutoExecSetting());
            dbSetting.setSettingKey(dbKey);
            dbSetting.setSettingValue(String.valueOf(settings.get(key)));
            autoExecSettingRepo.save(dbSetting);
        } catch (Exception e) {
            log.error("Failed to persist setting {} to DB: {}", key, e.getMessage());
        }
    }

    public List<Map<String, Object>> getExecLogs() {
        List<Map<String, Object>> list = new ArrayList<>(execLogs);
        Collections.reverse(list);
        return list.stream().limit(100).toList();
    }

    /**
     * Manual "Trade" button with a real (non-PAPER) broker selected. Reuses the same
     * order-placement machinery as auto-exec (dispatching on legList presence), but is
     * user-triggered rather than threshold-triggered, so it has its own broker resolution
     * and open-position gate instead of going through evaluateAndExecute's settings.
     */
    public Map<String, Object> manualExecuteLive(OptionArbOpportunity opp, int lots, String broker) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (opp == null) {
            result.put("status", "ERROR");
            result.put("message", "Opportunity not found");
            return result;
        }

        // LIVE positions only -- paper trades sitting open (from earlier testing, a different
        // mode, whatever) must never block a real order from being attempted.
        long openCount = positionRepo.countOpenLive();
        int maxOpen = ((Number) getSettings().getOrDefault("maxOpenPositions", 1)).intValue();
        if (openCount >= maxOpen) {
            result.put("status", "ERROR");
            result.put("message", "Already have " + openCount + "/" + maxOpen + " open positions. Close one first or raise Max Open Positions in Auto-Trade settings.");
            return result;
        }

        Long userId;
        BrokerAccount account;
        BrokerAdapter adapter;
        try {
            // Look up the account directly by the broker the caller actually asked to trade
            // on, instead of guessing a userId from "whichever account is ACTIVE and sorts
            // first" then hoping THAT user also has the requested broker active -- with
            // multiple users/brokers active at once that guess can resolve to the wrong user
            // entirely, producing "No <broker> account found" even when one clearly exists.
            List<BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
            if (accounts.isEmpty()) { result.put("status", "ERROR"); result.put("message", "No " + broker + " account found"); return result; }
            account = accounts.get(0);
            userId = account.getUserId();
            adapter = brokerService.getAdapter(broker);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Broker setup failed: " + e.getMessage());
            return result;
        }

        List<Map<String, Object>> legs = opp.getLegList();
        boolean isMultiLeg = legs != null && !legs.isEmpty();

        // Informational only -- logged so it's visible in Auto-Exec logs, but the manual
        // "Trade" button is user-initiated and should always actually reach the broker and
        // surface whatever the broker itself says (insufficient margin, market closed, AMO
        // rejection, whatever) rather than the app pre-judging and short-circuiting the call.
        try {
            BigDecimal availableMarginBd = adapter.getAvailableMargin(account.getAccessToken());
            double availableMargin = availableMarginBd != null ? availableMarginBd.doubleValue() : 0;
            int lotSize = getLotSize(opp.getUnderlying());
            double requiredMargin;
            if (isMultiLeg) {
                requiredMargin = estimateMultiLegMargin(legs, lotSize, lots);
            } else {
                String futSym = null, ceSym = null, peSym = null;
                if (opp.getExpiryDate() != null && opp.getStrike() != null) {
                    futSym = optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate());
                    ceSym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                    peSym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                }
                BigDecimal realMargin = adapter.getHedgedMargin(account.getAccessToken(), opp.getUnderlying(), futSym, ceSym, peSym, lotSize * lots);
                requiredMargin = realMargin != null ? realMargin.doubleValue() : estimateHedgedMargin(opp.getUnderlying(), lots);
            }
            if (requiredMargin > availableMargin * 0.9) {
                addLog("MARGIN", "MANUAL_LOW", opp.getUnderlying() + " " + opp.getStrike()
                    + " needs ~₹" + String.format("%.0f", requiredMargin) + " but only ₹" + String.format("%.0f", availableMargin)
                    + " available -- attempting anyway, broker will be the final word");
            }
        } catch (Exception e) {
            log.warn("Manual live-trade margin check failed for {}: {}", opp.getUnderlying(), e.getMessage());
        }

        boolean opened = isMultiLeg
                ? executeMultiLegTrade(account, adapter, opp, lots, userId, legs)
                : executeTrade(account, adapter, opp, lots, userId);

        if (opened) {
            result.put("status", "SUCCESS");
            result.put("underlying", opp.getUnderlying());
            result.put("strike", opp.getStrike());
            result.put("action", opp.getAction());
            result.put("message", opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction() + " entered LIVE via " + broker);
        } else {
            // executeMultiLegTrade/executeTrade already saved the real broker rejection
            // reason onto the LivePosition they created before returning false -- surface
            // that directly instead of a generic "check the logs" dead end.
            String detail = positionRepo.findByOpportunityIdIn(List.of(opp.getId())).stream()
                    .filter(p -> p.getErrorMessage() != null)
                    .reduce((first, second) -> second)
                    .map(LivePosition::getErrorMessage)
                    .orElse(null);
            result.put("status", "ERROR");
            result.put("message", detail != null ? detail : "Live order failed — no error detail was recorded, check Auto-Exec logs");
        }
        return result;
    }

    /**
     * User-initiated close of a single OPEN position (any strategy type, PAPER or live) --
     * reuses the same square-off + quote-mark-exit-price path the auto-exit/stop-loss loop
     * already uses, just triggered on demand instead of by a threshold. Uses the position's
     * own recorded broker (not the global auto-exec broker setting) since a position entered
     * live must be closed against the broker it was actually opened on.
     */
    public Map<String, Object> manualExitPosition(Long positionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        LivePosition pos = positionRepo.findById(positionId).orElse(null);
        if (pos == null) {
            result.put("status", "ERROR");
            result.put("message", "Position not found");
            return result;
        }
        if (!"OPEN".equals(pos.getStatus())) {
            result.put("status", "ERROR");
            result.put("message", "Position is " + pos.getStatus() + ", not OPEN -- nothing to close");
            return result;
        }

        boolean isMultiLeg = pos.getLegs() != null && !pos.getLegs().isEmpty();
        boolean isPaper = pos.getBroker() == null || "PAPER".equalsIgnoreCase(pos.getBroker());

        List<String> symbols = new ArrayList<>();
        if (isMultiLeg) {
            for (Map<String, Object> leg : pos.getLegs()) {
                Object sym = leg.get("symbol");
                if (sym instanceof String s) symbols.add(s);
            }
        } else {
            if (pos.getCeSymbol() != null) symbols.add(pos.getCeSymbol());
            if (pos.getPeSymbol() != null) symbols.add(pos.getPeSymbol());
            if (pos.getFutSymbol() != null) symbols.add(pos.getFutSymbol());
        }

        Map<String, OptionChainService.OptionQuote> quotes;
        try {
            quotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            log.warn("Manual exit quote fetch failed for position {}: {}", positionId, e.getMessage());
            quotes = Map.of();
        }

        double pnl = isMultiLeg ? computeMultiLegPnl(pos, quotes) : computePnl(pos, quotes);

        double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
        if (!isMultiLeg) {
            if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) ceCurrent = quotes.get(pos.getCeSymbol()).lastPrice;
            if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) peCurrent = quotes.get(pos.getPeSymbol()).lastPrice;
            if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) futCurrent = quotes.get(pos.getFutSymbol()).lastPrice;
        }

        boolean squaredOff;
        if (isPaper) {
            squaredOff = true;
            addLog("MANUAL_EXIT", "SQUARED_OFF", pos.getUnderlying() + " " + pos.getStrike()
                    + " — PAPER mode, closing at market price (P&L ₹" + String.format("%.0f", pnl) + ")");
        } else {
            try {
                // The position already knows exactly which user opened it (pos.userId, set at
                // entry) -- picking "whichever account happens to be ACTIVE and sorts first"
                // instead was the bug: with multiple users/brokers ACTIVE at once (PAPER for
                // user 2, ZERODHA for user 1, etc), findFirst() could resolve to a completely
                // different user than the one who actually holds this position, so the
                // broker-account lookup for the right broker+user combo came up empty even
                // though a valid ZERODHA account existed -- just not under the user id this
                // path guessed. Concrete symptom: closing a real open ZERODHA position failed
                // with "No active ZERODHA account found" while the account clearly existed.
                Long userId = pos.getUserId();
                List<BrokerAccount> accounts = userId != null
                        ? brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, pos.getBroker(), "ACTIVE")
                        : List.of();
                if (accounts.isEmpty()) {
                    result.put("status", "ERROR");
                    result.put("message", "No active " + pos.getBroker() + " account found for this position's user -- cannot place closing orders");
                    return result;
                }
                BrokerAccount account = accounts.get(0);
                BrokerAdapter adapter = brokerService.getAdapter(pos.getBroker());
                squaredOff = isMultiLeg ? squareOffMultiLegPosition(account, adapter, pos) : squareOffPosition(account, adapter, pos);
            } catch (Exception e) {
                result.put("status", "ERROR");
                result.put("message", "Broker close failed: " + e.getMessage());
                return result;
            }
        }

        if (!squaredOff) {
            addLog("MANUAL_EXIT", "SQUAREOFF_FAILED", "Failed to square off " + pos.getUnderlying() + " " + pos.getStrike());
            result.put("status", "ERROR");
            result.put("message", "Broker rejected one or more closing orders -- check Auto-Exec logs for the per-leg reason");
            return result;
        }

        pos.setStatus("CLOSED");
        pos.setExitedAt(LocalDateTime.now());
        pos.setCurrentPnl(BigDecimal.valueOf(pnl));
        if (isMultiLeg) {
            List<Map<String, Object>> legs = pos.getLegs();
            for (Map<String, Object> leg : legs) {
                String symbol = (String) leg.get("symbol");
                if (symbol != null && quotes.containsKey(symbol)) {
                    leg.put("exitPrice", quotes.get(symbol).lastPrice);
                }
            }
            pos.setLegs(legs);
        } else {
            pos.setCeExitPrice(BigDecimal.valueOf(ceCurrent));
            pos.setPeExitPrice(BigDecimal.valueOf(peCurrent));
            pos.setFutExitPrice(BigDecimal.valueOf(futCurrent));
        }
        positionRepo.save(pos);

        if (pos.getOpportunityId() != null) {
            try {
                oppRepo.findById(pos.getOpportunityId()).ifPresent(oppEntity -> {
                    oppEntity.setStatus("EXITED");
                    oppEntity.setExitTime(LocalDateTime.now());
                    oppEntity.setPnlAfterCosts(pos.getCurrentPnl());
                    oppRepo.save(oppEntity);
                });
            } catch (Exception e) {
                log.debug("Manual exit: opportunity update failed for {}: {}", pos.getOpportunityId(), e.getMessage());
            }
        }

        addLog("MANUAL_EXIT", "SUCCESS", pos.getUnderlying() + " " + pos.getStrike() + " " + pos.getAction()
                + " closed manually | P&L ₹" + String.format("%.0f", pnl));

        result.put("status", "SUCCESS");
        result.put("pnl", Math.round(pnl));
        result.put("message", pos.getUnderlying() + " " + pos.getStrike() + " closed | P&L ₹" + Math.round(pnl));
        return result;
    }

    /**
     * IMMEDIATE: called right after scan saves new opportunities.
     * Evaluates each new signal against thresholds and executes instantly.
     */
    public void evaluateAndExecuteFromMaps(List<Map<String, Object>> scanResults) {
        List<OptionArbOpportunity> opps = new ArrayList<>();
        for (Map<String, Object> m : scanResults) {
            try {
                OptionArbOpportunity opp = new OptionArbOpportunity();
                opp.setUnderlying((String) m.get("underlying"));
                opp.setStrike(m.get("strike") instanceof Number n ? n.intValue() : null);
                opp.setAction((String) m.get("action"));
                opp.setStrategyType((String) m.get("strategyType"));
                opp.setExpiryDate(m.get("expiryDate") instanceof String s ? LocalDate.parse(s) : null);
                Object eac = m.get("edgeAfterCosts");
                if (eac instanceof Number n) opp.setEdgeAfterCosts(BigDecimal.valueOf(n.doubleValue()));
                Object ce = m.get("ceEntryPrice");
                if (ce instanceof Number n) opp.setCeEntryPrice(BigDecimal.valueOf(n.doubleValue()));
                Object pe = m.get("peEntryPrice");
                if (pe instanceof Number n) opp.setPeEntryPrice(BigDecimal.valueOf(n.doubleValue()));
                Object fut = m.get("futuresPrice");
                if (fut instanceof Number n) opp.setFuturesPrice(BigDecimal.valueOf(n.doubleValue()));
                Object id = m.get("id");
                if (id instanceof Number n) opp.setId(n.longValue());
                Object legListObj = m.get("legList");
                if (legListObj instanceof List<?> ll) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> cast = (List<Map<String, Object>>) ll;
                        opp.setLegList(cast);
                    } catch (Exception ignored) {}
                }
                opps.add(opp);
            } catch (Exception ignored) {}
        }
        if (!opps.isEmpty()) evaluateAndExecute(opps);
    }

    public synchronized void evaluateAndExecute(List<OptionArbOpportunity> newOpps) {
        evaluateAndExecuteForMode(newOpps, "PAPER", autoExecSettings.getOrDefault("PAPER", Map.of()));
        evaluateAndExecuteForMode(newOpps, "LIVE", autoExecSettings.getOrDefault("LIVE", Map.of()));
    }
    

    private void executePaperTrades(List<OptionArbOpportunity> newOpps, Map<String, Object> settings) {
        long currentOpen = positionRepo.countOpenLive(); 
        for (OptionArbOpportunity opp : newOpps) {
            if (opp.getUnderlying() == null || opp.getEdgeAfterCosts() == null) continue;

            String prefix = strategyPrefix(opp.getStrategyType());
            String key = prefix + capitalize(opp.getUnderlying());
            boolean enabled = Boolean.TRUE.equals(settings.get(key + "Enabled"));
            if (!enabled) continue;

            double minEdge = ((Number) settings.getOrDefault(key + "MinEdge", 800.0)).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() < minEdge) continue;
            if (opp.getExpiryDate() == null || opp.getStrike() == null) continue;

            if (positionRepo.findByUserIdAndStatusOrderByEnteredAtDesc(1L, "OPEN").stream()
                    .anyMatch(p -> "PAPER".equals(p.getBroker()) && opp.getId() != null && opp.getId().equals(p.getOpportunityId()))) continue;

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            int lotSize = getLotSize(opp.getUnderlying());

            opp.setStatus("RUNNING");
            oppRepo.save(opp);

            List<Map<String, Object>> legs = opp.getLegList();
            boolean isMultiLeg = legs != null && !legs.isEmpty();

            if (isMultiLeg) {
                List<String> symbols = new ArrayList<>();
                List<Map<String, Object>> resolvedLegs = new ArrayList<>();
                for (Map<String, Object> leg : legs) {
                    int strike = ((Number) leg.get("strike")).intValue();
                    String optionType = (String) leg.get("optionType");
                    String sym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), strike, optionType);
                    Map<String, Object> resolved = new java.util.LinkedHashMap<>(leg);
                    resolved.put("symbol", sym);
                    resolvedLegs.add(resolved);
                    if (sym != null) symbols.add(sym);
                }
                Map<String, OptionChainService.OptionQuote> legQuotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
                double entryCost = 0;
                for (Map<String, Object> leg : resolvedLegs) {
                    String sym = (String) leg.get("symbol");
                    double live = (sym != null && legQuotes.containsKey(sym) && legQuotes.get(sym).lastPrice > 0)
                        ? legQuotes.get(sym).lastPrice
                        : (leg.get("price") instanceof Number n ? n.doubleValue() : 0);
                    leg.put("price", live);
                    int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                    boolean isBuy = "BUY".equals(leg.get("side"));
                    entryCost += (isBuy ? live : -live) * qtyMult;
                }
                entryCost = Math.abs(entryCost) * lotSize * lots;

                LivePosition livePos = LivePosition.builder()
                    .userId(1L).broker("PAPER").opportunityId(opp.getId())
                    .underlying(opp.getUnderlying()).strike(opp.getStrike()).action(opp.getAction())
                    .strategyType(opp.getStrategyType()).lots(lots).lotSize(lotSize)
                    .targetEdge(opp.getEdgeAfterCosts()).entryCost(BigDecimal.valueOf(entryCost))
                    .status("OPEN").enteredAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build();
                livePos.setLegs(resolvedLegs);
                positionRepo.save(livePos);
                addLog("EXEC", "PAPER", "Auto-executed PAPER trade for " + opp.getUnderlying() + " (" + resolvedLegs.size() + " legs)");
            } else {
                double ceLive = 0, peLive = 0;
                String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceSymbol, peSymbol));
                if (quotes.containsKey(ceSymbol) && quotes.get(ceSymbol).lastPrice > 0) ceLive = quotes.get(ceSymbol).lastPrice;
                if (quotes.containsKey(peSymbol) && quotes.get(peSymbol).lastPrice > 0) peLive = quotes.get(peSymbol).lastPrice;
                if (ceLive > 0) opp.setCeEntryPrice(BigDecimal.valueOf(ceLive));
                if (peLive > 0) opp.setPeEntryPrice(BigDecimal.valueOf(peLive));

                String futSymbol = optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate());
                double futLive = 0;
                if (futSymbol != null) {
                    try {
                        var futQuotes = optionChainService.fetchQuotes(List.of(futSymbol));
                        if (futQuotes.containsKey(futSymbol) && futQuotes.get(futSymbol).lastPrice > 0) futLive = futQuotes.get(futSymbol).lastPrice;
                    } catch (Exception ignored) {}
                }
                
                LivePosition livePos = LivePosition.builder()
                    .userId(1L).broker("PAPER").opportunityId(opp.getId())
                    .underlying(opp.getUnderlying()).strike(opp.getStrike()).action(opp.getAction())
                    .strategyType(opp.getStrategyType()).lots(lots).lotSize(lotSize)
                    .ceEntryPrice(ceLive > 0 ? BigDecimal.valueOf(ceLive) : null)
                    .peEntryPrice(peLive > 0 ? BigDecimal.valueOf(peLive) : null)
                    .futEntryPrice(futLive > 0 ? BigDecimal.valueOf(futLive) : null)
                    .futSymbol(futSymbol).ceSymbol(ceSymbol).peSymbol(peSymbol)
                    .targetEdge(opp.getEdgeAfterCosts())
                    .entryCost(BigDecimal.valueOf((ceLive + peLive + futLive) * lotSize))
                    .status("OPEN").enteredAt(LocalDateTime.now()).createdAt(LocalDateTime.now()).build();
                positionRepo.save(livePos);
                addLog("EXEC", "PAPER", "Auto-executed PAPER Bid Parity trade for " + opp.getUnderlying());
            }
        }
    }

    private void evaluateAndExecuteForMode(List<OptionArbOpportunity> newOpps, String mode, Map<String, Object> settings) {
        if (!Boolean.TRUE.equals(settings.get("enabled"))) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        if ("PAPER".equalsIgnoreCase(broker) || "PAPER".equalsIgnoreCase(mode)) {
            executePaperTrades(newOpps, settings);
            return;
        }
        int maxPositions = (int) settings.getOrDefault("maxOpenPositions", 1);
        long currentOpen = positionRepo.countOpenLive();
        if (currentOpen >= maxPositions) return;

        Long userId;
        BrokerAccount account;
        BrokerAdapter adapter;
        try {
            // See manualExecuteLive's fix for why this looks up by broker name directly
            // instead of guessing a userId from "whichever account is ACTIVE and sorts first".
            List<BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
            if (accounts.isEmpty()) return;
            account = accounts.get(0);
            userId = account.getUserId();
            adapter = brokerService.getAdapter(broker);
        } catch (Exception e) {
            log.error("Auto-exec: broker setup failed: {}", e.getMessage());
            return;
        }

        double maxDailyLoss = ((Number) settings.getOrDefault("maxDailyLoss", 5000.0)).doubleValue();
        double todayPnl = 0;
        try {
            List<LivePosition> openPos = positionRepo.findAllOpen();
            if (!openPos.isEmpty()) {
                List<String> syms = new ArrayList<>();
                for (LivePosition p : openPos) {
                    if (p.getCeSymbol() != null) syms.add(p.getCeSymbol());
                    if (p.getPeSymbol() != null) syms.add(p.getPeSymbol());
                    if (p.getFutSymbol() != null) syms.add(p.getFutSymbol());
                    if (p.getLegs() != null) {
                        for (Map<String, Object> leg : p.getLegs()) {
                            Object sym = leg.get("symbol");
                            if (sym instanceof String s) syms.add(s);
                        }
                    }
                }
                Map<String, OptionChainService.OptionQuote> q = syms.isEmpty() ? Map.of() : optionChainService.fetchQuotes(syms);
                for (LivePosition p : openPos) {
                    boolean isMultiLegPos = p.getLegs() != null && !p.getLegs().isEmpty();
                    todayPnl += isMultiLegPos ? computeMultiLegPnl(p, q) : computePnl(p, q);
                }
            }
        } catch (Exception e) {
            log.debug("Daily loss quote fetch failed, using stored values: {}", e.getMessage());
            todayPnl = positionRepo.findAllOpen().stream()
                    .filter(p -> p.getCurrentPnl() != null)
                    .mapToDouble(p -> p.getCurrentPnl().doubleValue())
                    .sum();
        }
        if (todayPnl < -maxDailyLoss) {
            addLog("RISK", "STOPPED", "Daily loss limit hit: ₹" + String.format("%.0f", todayPnl));
            return;
        }

        for (OptionArbOpportunity opp : newOpps) {
            if (currentOpen >= maxPositions) break;
            if (opp.getUnderlying() == null || opp.getEdgeAfterCosts() == null) continue;

            // Per-strategy, per-underlying key -- e.g. BOX_SPREAD on NIFTY reads
            // "boxNiftyEnabled"/"boxNiftyMinEdge"/"boxNiftyLots", independent of Bid Parity's
            // "bidParityNiftyEnabled" etc, even for the same underlying.
            String prefix = strategyPrefix(opp.getStrategyType());
            String key = prefix + capitalize(opp.getUnderlying());
            boolean enabled = Boolean.TRUE.equals(settings.get(key + "Enabled"));
            if (!enabled) continue;

            double minEdge = ((Number) settings.getOrDefault(key + "MinEdge", 800.0)).doubleValue();
            if (opp.getEdgeAfterCosts().doubleValue() < minEdge) continue;
            if (opp.getExpiryDate() == null || opp.getStrike() == null) continue;

            if (positionRepo.findByUserIdAndStatusOrderByEnteredAtDesc(userId, "OPEN").stream()
                    .anyMatch(p -> opp.getId() != null && opp.getId().equals(p.getOpportunityId()))) continue;

            int lots = ((Number) settings.getOrDefault(key + "Lots", 1)).intValue();
            List<Map<String, Object>> legs = opp.getLegList();
            boolean isMultiLeg = legs != null && !legs.isEmpty();

            // CRITICAL: Check margin BEFORE every single trade
            double availableMargin = 0;
            try {
                BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
                availableMargin = margin != null ? margin.doubleValue() : 0;
            } catch (Exception e) {
                log.error("Auto-exec: margin check failed mid-loop: {}", e.getMessage());
                addLog("MARGIN", "ERROR", "Failed to fetch margin mid-loop: " + e.getMessage());
                break;
            }

            double hedgedMargin;
            if (isMultiLeg) {
                // Vertical/Butterfly/Condor/Box/Iron Condor spreads are risk-defined (max loss
                // is bounded by the strike span) and carry no futures leg -- NAVIA's hedged-margin
                // API is shaped for the CE+PE+FUT conversion/reversal triple and doesn't apply
                // here. Use a conservative estimate (full strike span, always >= true max loss).
                int lotSize = getLotSize(opp.getUnderlying());
                hedgedMargin = estimateMultiLegMargin(legs, lotSize, lots);
                log.info("Multi-leg margin estimate for {} {} ({} legs): ₹{}", opp.getUnderlying(), opp.getStrike(),
                    legs.size(), String.format("%.0f", hedgedMargin));
            } else {
                double margin = 0;
                try {
                    int lotSize = getLotSize(opp.getUnderlying());
                    int marginQty = lotSize * lots;
                    // Build symbols for NAVIA margin API (OptionArbOpportunity doesn't store them)
                    String futSym = null, ceSym = null, peSym = null;
                    if (opp.getExpiryDate() != null && opp.getStrike() != null) {
                        futSym = optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate());
                        ceSym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                        peSym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                    }
                    BigDecimal realMargin = adapter.getHedgedMargin(
                        account.getAccessToken(), opp.getUnderlying(), futSym, ceSym, peSym, marginQty);
                    margin = realMargin != null ? realMargin.doubleValue() : estimateHedgedMargin(opp.getUnderlying(), lots);
                    log.info("Margin for {} {}: ₹{} (real={})", opp.getUnderlying(), opp.getStrike(),
                        String.format("%.0f", margin), realMargin != null);
                } catch (Exception e) {
                    margin = estimateHedgedMargin(opp.getUnderlying(), lots);
                }
                hedgedMargin = margin;
            }
            if (hedgedMargin > availableMargin * 0.9) {
                addLog("MARGIN", "SKIP", opp.getUnderlying() + " " + opp.getStrike()
                        + " needs ₹" + String.format("%.0f", hedgedMargin)
                        + " but only ₹" + String.format("%.0f", availableMargin) + " available");
                continue;
            }

            addLog("SIGNAL", "FIRING", opp.getUnderlying() + " " + opp.getStrike()
                    + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " > threshold ₹" + String.format("%.0f", minEdge) + " — executing NOW");
            boolean opened = isMultiLeg
                    ? executeMultiLegTrade(account, adapter, opp, lots, userId, legs)
                    : executeTrade(account, adapter, opp, lots, userId);
            if (opened) {
                currentOpen++;
                availableMargin -= hedgedMargin;
            }
        }
    }

    /**
     * Check all open positions for roll-over.
     * When live P&L reaches rolloverThresholdPct% of target edge, square off and open new position.
     */
    @Scheduled(fixedDelayString = "30000", initialDelay = 30000)
    public synchronized void checkRollover() {
        Map<String, Object> settings = getSettings();
        boolean autoExitEnabled = Boolean.TRUE.equals(settings.get("autoExitEnabled"));
        boolean stopLossEnabled = Boolean.TRUE.equals(settings.get("stopLossEnabled"));
        if (!autoExitEnabled && !stopLossEnabled) return;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 25))) return;

        double autoExitThresholdPct = ((Number) settings.getOrDefault("autoExitThresholdPct", 90.0)).doubleValue();

        List<LivePosition> openPositions = positionRepo.findAllOpen().stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .toList();
        if (openPositions.isEmpty()) return;

        List<String> symbols = new ArrayList<>();
        for (LivePosition p : openPositions) {
            if (p.getCeSymbol() != null) symbols.add(p.getCeSymbol());
            if (p.getPeSymbol() != null) symbols.add(p.getPeSymbol());
            if (p.getFutSymbol() != null) symbols.add(p.getFutSymbol());
            if (p.getLegs() != null) {
                for (Map<String, Object> leg : p.getLegs()) {
                    Object sym = leg.get("symbol");
                    if (sym instanceof String s) symbols.add(s);
                }
            }
        }
        if (symbols.isEmpty()) return;

        Map<String, OptionChainService.OptionQuote> quotes;
        try {
            quotes = optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            log.debug("Rollover quote fetch failed: {}", e.getMessage());
            return;
        }

        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);
        Long userId = null;
        BrokerAccount account = null;
        BrokerAdapter adapter = null;
        if (!isPaper) {
            try {
                List<BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
                if (accounts.isEmpty()) return;
                account = accounts.get(0);
                userId = account.getUserId();
                adapter = brokerService.getAdapter(broker);
            } catch (Exception e) {
                log.error("Rollover: broker setup failed: {}", e.getMessage());
                return;
            }
        }

        for (LivePosition pos : openPositions) {
            boolean isMultiLeg = pos.getLegs() != null && !pos.getLegs().isEmpty();
            double ceCurrent = 0, peCurrent = 0, futCurrent = 0;

            double pnl;
            if (isMultiLeg) {
                pnl = computeMultiLegPnl(pos, quotes);
            } else {
                if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) ceCurrent = quotes.get(pos.getCeSymbol()).lastPrice;
                if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) peCurrent = quotes.get(pos.getPeSymbol()).lastPrice;
                if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) futCurrent = quotes.get(pos.getFutSymbol()).lastPrice;

                double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
                double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
                double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
                int lotSize0 = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
                int lots0 = pos.getLots() != null ? pos.getLots() : 1;

                double legacyPnl = 0;
                String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
                if (ceCurrent > 0 || peCurrent > 0 || futCurrent > 0) {
                    if (action.contains("BUY CE +")) {
                        if (ceCurrent > 0 && ceEntry > 0) legacyPnl += ceCurrent - ceEntry;
                        if (peCurrent > 0 && peEntry > 0) legacyPnl += peEntry - peCurrent;
                        if (futCurrent > 0 && futEntry > 0) legacyPnl += futEntry - futCurrent;
                    } else if (action.contains("SELL CE +")) {
                        if (ceCurrent > 0 && ceEntry > 0) legacyPnl += ceEntry - ceCurrent;
                        if (peCurrent > 0 && peEntry > 0) legacyPnl += peCurrent - peEntry;
                        if (futCurrent > 0 && futEntry > 0) legacyPnl += futCurrent - futEntry;
                    } else {
                        if (ceCurrent > 0 && ceEntry > 0) legacyPnl += ceCurrent - ceEntry;
                        if (peCurrent > 0 && peEntry > 0) legacyPnl += peEntry - peCurrent;
                        if (futCurrent > 0 && futEntry > 0) legacyPnl += futEntry - futCurrent;
                    }
                }
                pnl = legacyPnl * lotSize0 * lots0;
            }

            int lots = pos.getLots() != null ? pos.getLots() : 1;
            double targetEdge = pos.getTargetEdge() != null ? pos.getTargetEdge().doubleValue() : 0;

            if (targetEdge <= 0) continue;

            double pnlPerLot = lots > 0 ? pnl / lots : 0;
            double pctAchieved = targetEdge > 0 ? (pnlPerLot / targetEdge) * 100 : 0;

            boolean shouldExit = false;
            String exitReason = "";

            double stopLossPct = ((Number) settings.getOrDefault("stopLossPct", 50.0)).doubleValue();

            // Stop-loss: close position if loss exceeds threshold
            if (stopLossEnabled && targetEdge > 0 && pnlPerLot < 0) {
                double lossPct = Math.abs(pnlPerLot / targetEdge) * 100;
                if (lossPct >= stopLossPct) {
                    shouldExit = true;
                    exitReason = "STOP_LOSS";
                    log.info("STOP_LOSS: {} {} strike {} — loss {}% exceeds threshold {}% (P&L ₹{})",
                            pos.getUnderlying(), pos.getAction(), pos.getStrike(), String.format("%.0f", lossPct),
                            String.format("%.0f", stopLossPct), String.format("%.0f", pnl));
                    addLog("STOP_LOSS", "TRIGGERED", pos.getUnderlying() + " " + pos.getStrike()
                            + " — loss " + String.format("%.0f", lossPct) + "% (₹" + String.format("%.0f", pnl) + ")");
                }
            }

            // Auto-exit: close position when target edge met (no re-entry)
            if (autoExitEnabled && pctAchieved >= autoExitThresholdPct) {
                shouldExit = true;
                exitReason = "AUTO_EXIT";
                log.info("AUTO_EXIT: {} {} strike {} — {}% of target ₹{} reached (P&L ₹{})",
                        pos.getUnderlying(), pos.getAction(), pos.getStrike(), String.format("%.0f", pctAchieved),
                        String.format("%.0f", targetEdge), String.format("%.0f", pnl));
                addLog("AUTO_EXIT", "TRIGGERED", pos.getUnderlying() + " " + pos.getStrike()
                        + " — " + String.format("%.0f", pctAchieved) + "% of target reached (₹" + String.format("%.0f", pnl) + ")");
            }

            if (!shouldExit) continue;

            // Close ALL legs (auto-exit or stop-loss)
            boolean squaredOff;
            if (isPaper) {
                squaredOff = true;
                addLog(exitReason, "SQUARED_OFF", pos.getUnderlying() + " " + pos.getStrike()
                        + " — PAPER mode, closing at market price (P&L ₹" + String.format("%.0f", pnl) + ")");
            } else {
                squaredOff = isMultiLeg ? squareOffMultiLegPosition(account, adapter, pos) : squareOffPosition(account, adapter, pos);
            }
            if (!squaredOff) {
                addLog(exitReason, "SQUAREOFF_FAILED", "Failed to square off " + pos.getUnderlying() + " " + pos.getStrike());
                continue;
            }

            // Save exit prices and close position
            pos.setStatus("CLOSED");
            pos.setExitedAt(LocalDateTime.now());
            pos.setCurrentPnl(BigDecimal.valueOf(pnl));
            if (isMultiLeg) {
                List<Map<String, Object>> legs = pos.getLegs();
                for (Map<String, Object> leg : legs) {
                    String symbol = (String) leg.get("symbol");
                    if (symbol != null && quotes.containsKey(symbol)) {
                        leg.put("exitPrice", quotes.get(symbol).lastPrice);
                    }
                }
                pos.setLegs(legs);
            } else {
                pos.setCeExitPrice(BigDecimal.valueOf(ceCurrent));
                pos.setPeExitPrice(BigDecimal.valueOf(peCurrent));
                pos.setFutExitPrice(BigDecimal.valueOf(futCurrent));
            }
            positionRepo.save(pos);

            // Update opportunity with exit time + P&L
            if (pos.getOpportunityId() != null) {
                try {
                    var oppOpt = oppRepo.findById(pos.getOpportunityId());
                    if (oppOpt.isPresent()) {
                        var oppEntity = oppOpt.get();
                        oppEntity.setStatus("EXITED");
                        oppEntity.setExitTime(LocalDateTime.now());
                        oppEntity.setPnlAfterCosts(pos.getCurrentPnl());
                        oppEntity.setCeExitPrice(BigDecimal.valueOf(ceCurrent));
                        oppEntity.setPeExitPrice(BigDecimal.valueOf(peCurrent));
                        oppRepo.save(oppEntity);
                    }
                } catch (Exception e) {
                    log.debug("Failed to update opportunity status on close: {}", e.getMessage());
                }
            }

            addLog(exitReason, "SQUARED_OFF", pos.getUnderlying() + " " + pos.getStrike() + " — P&L ₹" + String.format("%.0f", pnl));
        }
    }

    private boolean squareOffPosition(BrokerAccount account, BrokerAdapter adapter, LivePosition pos) {
        try {
            int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
            int lots = pos.getLots() != null ? pos.getLots() : 1;
            int qty = lotSize * lots;
            String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";

            List<PlannedLeg> closePlan;
            if (action.contains("BUY CE +")) {
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "ce"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "pe"),
                    new PlannedLeg(pos.getFutSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "fut")
                );
            } else if (action.contains("SELL CE +")) {
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "ce"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "pe"),
                    new PlannedLeg(pos.getFutSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "fut")
                );
            } else {
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "ce"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "pe"),
                    new PlannedLeg(pos.getFutSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "fut")
                );
            }

            boolean allConfirmed = true;
            for (PlannedLeg leg : closePlan) {
                allConfirmed &= placeAndVerifyCloseOrder(account, adapter, leg.symbol(), leg.side(), leg.quantity(), leg.legKey());
            }
            return allConfirmed;
        } catch (Exception e) {
            log.error("Rollover square-off failed: {}", e.getMessage());
            return false;
        }
    }

    public synchronized Map<String, Object> rollPosition(Long positionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        LivePosition pos = positionRepo.findById(positionId).orElse(null);
        if (pos == null) { result.put("error", "Position not found"); return result; }
        if (!"OPEN".equals(pos.getStatus())) { result.put("error", "Position is " + pos.getStatus() + ", not OPEN"); return result; }
        if (pos.getLegs() != null && !pos.getLegs().isEmpty()) {
            result.put("error", "Manual rollover isn't supported for multi-leg spreads (Box/Vertical/Butterfly/Condor/Iron Condor) — square off and re-enter manually instead.");
            return result;
        }

        Map<String, Object> settings = getSettings();
        String broker = (String) settings.getOrDefault("broker", "NAVIA");
        boolean isPaper = "PAPER".equalsIgnoreCase(broker);

        List<String> symbols = new ArrayList<>();
        if (pos.getCeSymbol() != null) symbols.add(pos.getCeSymbol());
        if (pos.getPeSymbol() != null) symbols.add(pos.getPeSymbol());
        if (pos.getFutSymbol() != null) symbols.add(pos.getFutSymbol());

        Map<String, OptionChainService.OptionQuote> quotes;
        try {
            quotes = optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            result.put("error", "Quote fetch failed: " + e.getMessage());
            return result;
        }

        double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
        if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) ceCurrent = quotes.get(pos.getCeSymbol()).lastPrice;
        if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) peCurrent = quotes.get(pos.getPeSymbol()).lastPrice;
        if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) futCurrent = quotes.get(pos.getFutSymbol()).lastPrice;

        double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
        double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
        double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
        int lots = pos.getLots() != null ? pos.getLots() : 1;

        double pnl = 0;
        String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
        if (action.contains("BUY CE +")) {
            if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
            if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
            if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
        } else if (action.contains("SELL CE +")) {
            if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
            if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
            if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
        }
        pnl *= lotSize * lots;

        boolean success;
        if (isPaper) {
            success = true;
            addLog("MANUAL_ROLLOVER", "PAPER_ROLL", pos.getUnderlying() + " " + pos.getStrike()
                    + " — PAPER mode, rolling CE+PE (P&L ₹" + String.format("%.0f", pnl) + ")");
        } else {
            BrokerAccount account = null;
            BrokerAdapter adapter = null;
            try {
                Long userId = pos.getUserId();
                List<BrokerAccount> accounts = userId != null
                        ? brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(userId, broker, "ACTIVE")
                        : List.of();
                if (accounts.isEmpty()) { result.put("error", "No " + broker + " account found for this position's user"); return result; }
                account = accounts.get(0);
                adapter = brokerService.getAdapter(broker);
            } catch (Exception e) {
                result.put("error", "Broker setup failed: " + e.getMessage());
                return result;
            }
            success = rollOptionsOnly(account, adapter, pos, ceCurrent, peCurrent, futCurrent, pnl);
        }

        if (success) {
            result.put("success", true);
            result.put("positionId", pos.getId());
            result.put("underlying", pos.getUnderlying());
            result.put("strike", pos.getStrike());
            result.put("pnl", Math.round(pnl));
            result.put("message", "CE+PE rolled, FUT kept");
            addLog("MANUAL_ROLLOVER", "ROLLED", pos.getUnderlying() + " " + pos.getStrike()
                    + " — P&L ₹" + String.format("%.0f", pnl));
        } else {
            result.put("error", "Rollover order failed");
            addLog("MANUAL_ROLLOVER", "FAILED", pos.getUnderlying() + " " + pos.getStrike());
        }
        return result;
    }

    private boolean rollOptionsOnly(BrokerAccount account, BrokerAdapter adapter, LivePosition pos,
                                     double ceCurrent, double peCurrent, double futCurrent, double pnl) {
        try {
            int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
            int lots = pos.getLots() != null ? pos.getLots() : 1;
            int qty = lotSize * lots;
            String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";

            // Step 1: Close existing CE+PE legs (opposite direction)
            List<PlannedLeg> closePlan;
            if (action.contains("BUY CE +")) {
                // Was long CE, short PE → close: SELL CE, BUY PE
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "ce-close"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "pe-close")
                );
            } else {
                // Was short CE, long PE → close: BUY CE, SELL PE
                closePlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "ce-close"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "pe-close")
                );
            }

            addLog("ROLLOVER", "CLOSING_OPTIONS", "Closing CE+PE legs (FUT kept)...");
            for (PlannedLeg leg : closePlan) {
                BrokerOrderRequest req = BrokerOrderRequest.builder()
                        .symbol(leg.symbol()).exchange("NFO")
                        .side(leg.side()).quantity(leg.quantity())
                        .price(leg.price())
                        .orderType(BrokerOrderRequest.OrderType.MARKET)
                        .productType("NRML").build();
                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);
                if (!resp.isSuccess()) {
                    addLog("ROLLOVER", "LEG_FAIL", leg.legKey() + " " + leg.symbol() + ": " + resp.message());
                    return false;
                }
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }

            // Step 2: Enter new CE+PE legs (same direction as original)
            List<PlannedLeg> entryPlan;
            if (action.contains("BUY CE +")) {
                // Re-enter: BUY CE, SELL PE
                entryPlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "ce-entry"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "pe-entry")
                );
            } else {
                // Re-enter: SELL CE, BUY PE
                entryPlan = List.of(
                    new PlannedLeg(pos.getCeSymbol(), BrokerOrderRequest.Side.SELL, qty, 0.0, "ce-entry"),
                    new PlannedLeg(pos.getPeSymbol(), BrokerOrderRequest.Side.BUY, qty, 0.0, "pe-entry")
                );
            }

            addLog("ROLLOVER", "ENTERING_OPTIONS", "Entering new CE+PE legs...");
            for (PlannedLeg leg : entryPlan) {
                BrokerOrderRequest req = BrokerOrderRequest.builder()
                        .symbol(leg.symbol()).exchange("NFO")
                        .side(leg.side()).quantity(leg.quantity())
                        .price(leg.price())
                        .orderType(BrokerOrderRequest.OrderType.MARKET)
                        .productType("NRML").build();
                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);
                if (!resp.isSuccess()) {
                    addLog("ROLLOVER", "LEG_FAIL", leg.legKey() + " " + leg.symbol() + ": " + resp.message());
                    return false;
                }
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }

            // Step 3: Update position with new CE/PE entry prices (FUT stays, don't update its entry)
            pos.setCeEntryPrice(BigDecimal.valueOf(ceCurrent));
            pos.setPeEntryPrice(BigDecimal.valueOf(peCurrent));
            pos.setCurrentPnl(BigDecimal.valueOf(pnl));
            pos.setEnteredAt(LocalDateTime.now());
            positionRepo.save(pos);

            // Recalculate target edge with new prices
            double newTarget = recalculateTargetEdge(ceCurrent, peCurrent, futCurrent,
                    pos.getStrike(), pos.getAction(), pos.getUnderlying());
            pos.setTargetEdge(BigDecimal.valueOf(newTarget));
            positionRepo.save(pos);

            addLog("ROLLOVER", "OPTIONSROLLED", pos.getUnderlying() + " " + pos.getStrike()
                    + " — CE+PE rolled, FUT kept (saved ~₹384 in FUT charges)");
            return true;
        } catch (Exception e) {
            log.error("rollOptionsOnly failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean executeTrade(BrokerAccount account, BrokerAdapter adapter, OptionArbOpportunity opp, int lots, Long userId) {
        int lotSize = getLotSize(opp.getUnderlying());
        String action = opp.getAction() != null ? opp.getAction().toUpperCase() : "";
        boolean isConversion = "CONVERSION".equals(action) || action.contains("BUY CE +");

        LivePosition position = LivePosition.builder()
                .userId(userId)
                .broker(account.getBrokerName())
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(opp.getAction())
                .strategyType(opp.getStrategyType())
                .expiryDate(opp.getExpiryDate())
                .lots(lots)
                .lotSize(lotSize)
                .ceEntryPrice(opp.getCeEntryPrice())
                .peEntryPrice(opp.getPeEntryPrice())
                .futEntryPrice(opp.getFuturesPrice())
                .targetEdge(BigDecimal.valueOf(recalculateTargetEdge(
                    opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0,
                    opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0,
                    opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0,
                    opp.getStrike(), opp.getAction(), opp.getUnderlying(),
                    opp.getExpiryDate() != null ? java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), opp.getExpiryDate()) : 0)))
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
            String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
            String futSymbol = optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate());
            position.setCeSymbol(ceSymbol);
            position.setPeSymbol(peSymbol);
            position.setFutSymbol(futSymbol);

            int ceQty = lots * lotSize;
            int peQty = lots * lotSize;
            int futQty = lots * lotSize;

            // BUY-first: place BUY legs first, then hedge with sell legs.
            // CONVERSION: BUY CE, SELL FUT, SELL PE
            // REVERSAL: BUY PE, BUY FUT, SELL CE
            List<PlannedLeg> orderPlan;
            if (isConversion) {
                orderPlan = List.of(
                    new PlannedLeg(ceSymbol, BrokerOrderRequest.Side.BUY, ceQty, opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() * 1.05 : 0.0, "ce"),
                    new PlannedLeg(futSymbol, BrokerOrderRequest.Side.SELL, futQty, opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() * 0.95 : 0.0, "fut"),
                    new PlannedLeg(peSymbol, BrokerOrderRequest.Side.SELL, peQty, opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() * 0.95 : 0.0, "pe")
                );
            } else {
                orderPlan = List.of(
                    new PlannedLeg(peSymbol, BrokerOrderRequest.Side.BUY, peQty, opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() * 1.05 : 0.0, "pe"),
                    new PlannedLeg(futSymbol, BrokerOrderRequest.Side.BUY, futQty, opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() * 1.05 : 0.0, "fut"),
                    new PlannedLeg(ceSymbol, BrokerOrderRequest.Side.SELL, ceQty, opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() * 0.95 : 0.0, "ce")
                );
            }

            List<PlacedLeg> placedLegs = new ArrayList<>();

            for (PlannedLeg leg : orderPlan) {
                String symbol = leg.symbol();
                BrokerOrderRequest.Side side = leg.side();
                int qty = leg.quantity();
                double price = leg.price();
                String legKey = leg.legKey();

                BrokerOrderRequest req = BrokerOrderRequest.builder()
                        .symbol(symbol).exchange("NFO")
                        .side(side)
                        .quantity(qty).price(price)
                        .orderType(price > 0 ? BrokerOrderRequest.OrderType.LIMIT : BrokerOrderRequest.OrderType.MARKET)
                        .productType("NRML").build();
                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);

                if (!resp.isSuccess() || resp.orderId() == null || resp.orderId().isBlank()) {
                    log.warn("Auto-exec: {} {} order failed: {}", legKey, symbol, resp.message());
                    addLog("EXEC", "LEG_FAILED", legKey + " " + symbol + " " + side + ": " + resp.message());
                    cancelPendingLegs(account, adapter, placedLegs);
                    position.setStatus("FAILED");
                    position.setErrorMessage(legKey + " order failed: " + resp.message());
                    positionRepo.save(position);
                    addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike() + " — cancelled all legs");
                    return false;
                }

                placedLegs.add(new PlacedLeg(leg, resp.orderId(), resp.status()));
                if ("ce".equals(legKey)) position.setCeOrderId(resp.orderId());
                else if ("pe".equals(legKey)) position.setPeOrderId(resp.orderId());
                else if ("fut".equals(legKey)) position.setFutOrderId(resp.orderId());

                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }

            awaitFinalStatuses(account, adapter, placedLegs);
            List<PlacedLeg> filledLegs = placedLegs.stream()
                    .filter(leg -> isCompleteStatus(leg.status))
                    .toList();
            List<PlacedLeg> nonCompleteLegs = placedLegs.stream()
                    .filter(leg -> !isCompleteStatus(leg.status))
                    .toList();

            if (filledLegs.size() != orderPlan.size()) {
                cancelPendingLegs(account, adapter, nonCompleteLegs);
                squareOffFilledLegs(account, adapter, filledLegs);
                position.setStatus(filledLegs.isEmpty() ? "FAILED" : "PARTIAL");
                position.setErrorMessage("Only " + filledLegs.size() + "/" + orderPlan.size() + " legs filled. Pending orders cancelled and filled legs squared off.");
                positionRepo.save(position);
                addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike()
                        + " — only " + filledLegs.size() + "/" + orderPlan.size() + " legs filled; reverted trade");
                return false;
            }

            position.setStatus("OPEN");
            position.setEntryCost(BigDecimal.valueOf(estimateEntryCost(opp, lots)));
            positionRepo.save(position);

            addLog("EXEC", "SUCCESS", opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction()
                    + " | Lots=" + lots + " Edge=₹" + String.format("%.0f", opp.getEdgeAfterCosts().doubleValue())
                    + " | 3-leg box fully filled");
            return true;

        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Generic N-leg executor for spreads with no futures leg and 2-4 same-underlying option
     * legs (Vertical/Butterfly/Condor/Box/Iron Condor). Reuses the existing order-placement,
     * cancel, square-off, and status-polling machinery below -- those are already leg-agnostic;
     * only leg construction and P&L accounting differ from the CE+PE+FUT executor above.
     */
    private boolean executeMultiLegTrade(BrokerAccount account, BrokerAdapter adapter, OptionArbOpportunity opp,
                                          int lots, Long userId, List<Map<String, Object>> rawLegs) {
        int lotSize = getLotSize(opp.getUnderlying());

        // Placeholder for FAILED/PARTIAL outcomes below, where no real fill price exists to
        // compute from -- overwritten with a real fill-price-based figure once legs actually
        // fill (see realTargetEdge further down).
        double targetEdge = opp.getEdgeAfterCosts() != null ? opp.getEdgeAfterCosts().doubleValue() : 0;

        LivePosition position = LivePosition.builder()
                .userId(userId)
                .broker(account.getBrokerName())
                .opportunityId(opp.getId())
                .underlying(opp.getUnderlying())
                .strike(opp.getStrike())
                .action(opp.getAction())
                .strategyType(opp.getStrategyType())
                .expiryDate(opp.getExpiryDate())
                .lots(lots)
                .lotSize(lotSize)
                .targetEdge(BigDecimal.valueOf(targetEdge))
                .status("EXECUTING")
                .enteredAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            List<LegPair> pairs = new ArrayList<>();
            for (Map<String, Object> spec : rawLegs) {
                int strike = ((Number) spec.get("strike")).intValue();
                String optionType = (String) spec.get("optionType");
                String side = (String) spec.get("side");
                int qtyMult = spec.get("qty") instanceof Number n ? n.intValue() : 1;
                String symbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), strike, optionType);
                int qty = lots * lotSize * qtyMult;
                double specPrice = spec.get("price") instanceof Number n ? n.doubleValue() : 0.0;
                // Apply 5% buffer: BUY +5%, SELL -5%. Converts MARKET to guaranteed-fill LIMIT.
                double bufferedPrice = specPrice > 0
                        ? ("BUY".equals(side) ? Math.ceil(specPrice * 1.05 * 20) / 20.0
                                              : Math.floor(specPrice * 0.95 * 20) / 20.0)
                        : 0.0;
                PlannedLeg planned = new PlannedLeg(symbol,
                        "BUY".equals(side) ? BrokerOrderRequest.Side.BUY : BrokerOrderRequest.Side.SELL,
                        qty, bufferedPrice, optionType + strike);
                pairs.add(new LegPair(spec, planned));
            }

            // BUY-first ordering, matching the existing 3-leg executor's convention.
            pairs.sort((a, b) -> {
                boolean aBuy = a.planned().side() == BrokerOrderRequest.Side.BUY;
                boolean bBuy = b.planned().side() == BrokerOrderRequest.Side.BUY;
                return aBuy == bBuy ? 0 : (aBuy ? -1 : 1);
            });

            List<PlacedLeg> placedLegs = new ArrayList<>();
            for (LegPair pair : pairs) {
                PlannedLeg leg = pair.planned();
                // Use LIMIT with buffered price if available, else MARKET
                BrokerOrderRequest.OrderType orderType = leg.price() > 0
                        ? BrokerOrderRequest.OrderType.LIMIT
                        : BrokerOrderRequest.OrderType.MARKET;
                BrokerOrderRequest req = BrokerOrderRequest.builder()
                        .symbol(leg.symbol()).exchange("NFO")
                        .side(leg.side())
                        .quantity(leg.quantity()).price(leg.price())
                        .orderType(orderType)
                        .productType("NRML").build();
                BrokerOrderResponse resp = adapter.placeOrder(account.getAccessToken(), req);

                if (!resp.isSuccess() || resp.orderId() == null || resp.orderId().isBlank()) {
                    log.warn("Auto-exec multi-leg: {} {} order failed: {}", leg.legKey(), leg.symbol(), resp.message());
                    addLog("EXEC", "LEG_FAILED", leg.legKey() + " " + leg.symbol() + " " + leg.side() + ": " + resp.message());
                    cancelPendingLegs(account, adapter, placedLegs);
                    boolean squaredOffOk = squareOffFilledLegs(account, adapter, placedLegs);
                    position.setStatus("FAILED");
                    position.setErrorMessage(leg.legKey() + " order failed: " + resp.message()
                            + (squaredOffOk ? " (other legs squared off, confirmed flat)"
                                            : " (WARNING: one or more other legs may still be open -- square-off unconfirmed, check Auto-Exec logs)"));
                    position.setLegs(buildLegsJson(pairs, placedLegs));
                    positionRepo.save(position);
                    addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike() + " — cancelled all legs (" + pairs.size() + "-leg)");
                    return false;
                }

                placedLegs.add(new PlacedLeg(leg, resp.orderId(), resp.status()));
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }

            awaitFinalStatuses(account, adapter, placedLegs);
            List<PlacedLeg> filledLegs = placedLegs.stream().filter(l -> isCompleteStatus(l.status)).toList();
            List<PlacedLeg> nonCompleteLegs = placedLegs.stream().filter(l -> !isCompleteStatus(l.status)).toList();

            if (filledLegs.size() != pairs.size()) {
                cancelPendingLegs(account, adapter, nonCompleteLegs);
                boolean squaredOffOk = squareOffFilledLegs(account, adapter, filledLegs);
                position.setStatus(filledLegs.isEmpty() ? "FAILED" : "PARTIAL");
                position.setErrorMessage("Only " + filledLegs.size() + "/" + pairs.size() + " legs filled. Pending orders cancelled, "
                        + (squaredOffOk ? "filled legs confirmed squared off."
                                        : "WARNING: filled-leg square-off unconfirmed -- one or more legs may still be open, check Auto-Exec logs."));
                position.setLegs(buildLegsJson(pairs, placedLegs));
                positionRepo.save(position);
                addLog("EXEC", "FAILED", opp.getUnderlying() + " " + opp.getStrike()
                        + " — only " + filledLegs.size() + "/" + pairs.size() + " legs filled; reverted trade (" + pairs.size() + "-leg)");
                return false;
            }

            position.setStatus("OPEN");
            position.setLegs(buildLegsJson(pairs, placedLegs));
            double costPerShare = pairs.stream().mapToDouble(p -> {
                double price = p.spec().get("price") instanceof Number n ? n.doubleValue() : 0;
                int qtyMult = p.spec().get("qty") instanceof Number n ? n.intValue() : 1;
                boolean isBuy = p.planned().side() == BrokerOrderRequest.Side.BUY;
                return (isBuy ? price : -price) * qtyMult;
            }).sum();
            double entryCost = costPerShare * lotSize * lots;
            position.setEntryCost(BigDecimal.valueOf(Math.abs(entryCost)));

            // Target edge at detection time (opp.edgeAfterCosts) reflects the quotes the
            // scanner saw when it FOUND the signal, not what actually got filled -- by
            // execution time the book can have moved (or the order can even AMO-queue for
            // hours), so the "edge" that was real at detection may have partly or fully
            // evaporated, or moved further in the other direction. Recompute the real best-case
            // per-lot profit from the actual filled-request legs: payoff(x) is piecewise-linear
            // in settlement price with kinks only at the position's own strikes, so its maximum
            // is guaranteed to land exactly on one of those strikes -- no need to sweep a price
            // range, just evaluate profit at each distinct strike and take the best.
            // (Earlier version of this fix used maxStrike-minStrike as "width", which is wrong
            // for a butterfly: peak profit is at the CENTER strike, e.g. 200 points for a
            // 24050/24250/24450 fly, not the full 400-point outer span -- caught by hand-cross-
            // checking against AlgoTest's numbers before this shipped.)
            Set<Integer> candidateStrikes = pairs.stream()
                    .map(p -> ((Number) p.spec().get("strike")).intValue())
                    .collect(Collectors.toCollection(TreeSet::new));
            double maxProfitPerShare = candidateStrikes.stream().mapToDouble(x -> {
                double payoff = pairs.stream().mapToDouble(p -> {
                    int strike = ((Number) p.spec().get("strike")).intValue();
                    String optType = (String) p.spec().get("optionType");
                    int qtyMult = p.spec().get("qty") instanceof Number n ? n.intValue() : 1;
                    boolean isBuy = p.planned().side() == BrokerOrderRequest.Side.BUY;
                    double intrinsic = "PE".equalsIgnoreCase(optType) ? Math.max(strike - x, 0) : Math.max(x - strike, 0);
                    return (isBuy ? intrinsic : -intrinsic) * qtyMult;
                }).sum();
                return payoff - costPerShare;
            }).max().orElse(-costPerShare);
            double realTargetEdge = maxProfitPerShare * lotSize;
            position.setTargetEdge(BigDecimal.valueOf(realTargetEdge));

            positionRepo.save(position);

            addLog("EXEC", "SUCCESS", opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction()
                    + " | Lots=" + lots + " Edge=₹" + String.format("%.0f", realTargetEdge)
                    + " (detection-time was ₹" + String.format("%.0f", opp.getEdgeAfterCosts() != null ? opp.getEdgeAfterCosts().doubleValue() : 0) + ")"
                    + " | " + pairs.size() + "-leg spread fully filled");
            return true;

        } catch (Exception e) {
            position.setStatus("FAILED");
            position.setErrorMessage(e.getMessage());
            positionRepo.save(position);
            addLog("EXEC", "ERROR", opp.getUnderlying() + " " + opp.getStrike() + ": " + e.getMessage());
            return false;
        }
    }

    private List<Map<String, Object>> buildLegsJson(List<LegPair> pairs, List<PlacedLeg> placedLegs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            LegPair p = pairs.get(i);
            PlacedLeg placed = i < placedLegs.size() ? placedLegs.get(i) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strike", p.spec().get("strike"));
            m.put("optionType", p.spec().get("optionType"));
            m.put("side", p.spec().get("side"));
            m.put("qty", p.spec().get("qty"));
            m.put("price", p.spec().get("price"));
            m.put("symbol", p.planned().symbol());
            m.put("orderId", placed != null ? placed.orderId : null);
            m.put("status", placed != null ? placed.status : null);
            out.add(m);
        }
        return out;
    }

    /**
     * Conservative margin estimate for a defined-risk multi-leg spread with no futures leg:
     * the full strike span times lot size is always >= the true max loss (width minus any net
     * credit received / debit paid), so this never under-reserves margin.
     */
    private double estimateMultiLegMargin(List<Map<String, Object>> legs, int lotSize, int lots) {
        int minStrike = Integer.MAX_VALUE, maxStrike = Integer.MIN_VALUE;
        for (Map<String, Object> leg : legs) {
            int strike = ((Number) leg.get("strike")).intValue();
            minStrike = Math.min(minStrike, strike);
            maxStrike = Math.max(maxStrike, strike);
        }
        double span = Math.max(0, maxStrike - minStrike);
        return span * lotSize * lots * 1.15;
    }

    /**
     * Generic mark-to-market P&L for a multi-leg (no-futures) position: sum of per-leg
     * (current - entry) for BUY legs, (entry - current) for SELL legs, scaled by each leg's
     * quantity multiplier and lot size.
     */
    public double computeMultiLegPnl(LivePosition pos, Map<String, OptionChainService.OptionQuote> quotes) {
        List<Map<String, Object>> legs = pos.getLegs();
        if (legs == null || legs.isEmpty()) return 0;
        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
        int lots = pos.getLots() != null ? pos.getLots() : 1;
        double pnl = 0;
        for (Map<String, Object> leg : legs) {
            String symbol = (String) leg.get("symbol");
            if (symbol == null || !quotes.containsKey(symbol)) continue;
            OptionChainService.OptionQuote q = quotes.get(symbol);
            double bid = q.bid > 0 ? q.bid : q.lastPrice;
            double ask = q.ask > 0 ? q.ask : q.lastPrice;
            double entry = leg.get("price") instanceof Number n ? n.doubleValue() : 0;
            if (q.lastPrice <= 0 || entry <= 0) continue;
            int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
            String side = (String) leg.get("side");
            // To close a BUY leg, we must SELL at the BID
            // To close a SELL leg, we must BUY at the ASK
            double legPnl = "BUY".equals(side) ? (bid - entry) : (entry - ask);
            pnl += legPnl * qtyMult;
        }
        return pnl * lotSize * lots;
    }

    /**
     * Generic square-off for a multi-leg position: reverse each leg's side, MARKET order.
     */
    /** @return true only if every leg's closing order was confirmed FILLED (see
     *  placeAndVerifyCloseOrder), not merely accepted by the broker. */
    public boolean squareOffMultiLegPosition(BrokerAccount account, BrokerAdapter adapter, LivePosition pos) {
        List<Map<String, Object>> legs = pos.getLegs();
        if (legs == null || legs.isEmpty()) return false;
        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
        int lots = pos.getLots() != null ? pos.getLots() : 1;
        boolean allConfirmed = true;
        for (Map<String, Object> leg : legs) {
            String symbol = (String) leg.get("symbol");
            String side = (String) leg.get("side");
            int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
            if (symbol == null || side == null) { allConfirmed = false; continue; }
            BrokerOrderRequest.Side closeSide = "BUY".equals(side) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY;
            allConfirmed &= placeAndVerifyCloseOrder(account, adapter, symbol, closeSide, lotSize * lots * qtyMult, symbol);
        }
        return allConfirmed;
    }

    private record LegPair(Map<String, Object> spec, PlannedLeg planned) {}

    private void awaitFinalStatuses(BrokerAccount account, BrokerAdapter adapter, List<PlacedLeg> placedLegs) {
        for (int attempt = 0; attempt < 10; attempt++) {
            boolean allComplete = true;
            boolean anyTerminalFailure = false;
            boolean anyUnknown = false;
            for (PlacedLeg leg : placedLegs) {
                String latest = adapter.getOrderStatus(account.getAccessToken(), leg.orderId);
                if (latest != null && !latest.isBlank() && !"UNKNOWN".equalsIgnoreCase(latest)) {
                    leg.status = latest;
                } else {
                    anyUnknown = true;
                }
                allComplete &= isCompleteStatus(leg.status);
                anyTerminalFailure |= isFailureStatus(leg.status);
            }
            log.info("awaitFinalStatuses attempt {}: allComplete={} anyTerminalFailure={} anyUnknown={} statuses={}",
                    attempt, allComplete, anyTerminalFailure, anyUnknown,
                    placedLegs.stream().map(l -> l.leg.legKey() + "=" + l.status).toList());
            if (allComplete || anyTerminalFailure) {
                return;
            }
            if (anyUnknown && attempt >= 5) {
                log.warn("Navia OrderBook auth failing after {} polls — orders likely filled on exchange, marking all as COMPLETE", attempt + 1);
                for (PlacedLeg leg : placedLegs) {
                    if (!isCompleteStatus(leg.status) && !isFailureStatus(leg.status)) {
                        log.warn("Marking {} as COMPLETE (OrderBook auth failure after {} polls)", leg.leg.legKey(), attempt + 1);
                        leg.status = "COMPLETE";
                    }
                }
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Places a closing order and polls it to a FINAL status before trusting it -- the broker's
     * immediate response only confirms the order was accepted (e.g. "OPEN"), not that it
     * actually filled. Every square-off call site used to fire-and-forget on that acceptance
     * alone, so a close order that got rejected, stuck, or partially filled left a real naked
     * leg on the books with nothing retrying it and nothing loudly flagging it. Retries once,
     * and if still unconfirmed, logs a CRITICAL entry -- that state needs a human, not another
     * silent retry loop.
     */
    private boolean placeAndVerifyCloseOrder(BrokerAccount account, BrokerAdapter adapter,
                                              String symbol, BrokerOrderRequest.Side closeSide, int qty, String legKey) {
        if (symbol == null || symbol.isBlank()) return true;
        for (int attempt = 1; attempt <= 2; attempt++) {
            BrokerOrderRequest closeReq = BrokerOrderRequest.builder()
                    .symbol(symbol).exchange("NFO")
                    .side(closeSide).quantity(qty)
                    .price(0.0).orderType(BrokerOrderRequest.OrderType.MARKET)
                    .productType("NRML").build();
            BrokerOrderResponse closeResp;
            try {
                closeResp = adapter.placeOrder(account.getAccessToken(), closeReq);
            } catch (Exception e) {
                log.error("Square-off placement failed for {} {} (attempt {}): {}", legKey, symbol, attempt, e.getMessage());
                addLog("EXEC", "SQUARE_OFF_FAILED", legKey + " " + symbol + " attempt " + attempt + ": " + e.getMessage());
                continue;
            }
            if (!closeResp.isSuccess() || closeResp.orderId() == null || closeResp.orderId().isBlank()) {
                addLog("EXEC", "SQUARE_OFF_FAILED", legKey + " " + symbol + " attempt " + attempt + ": " + closeResp.message());
                continue;
            }
            String status = closeResp.status();
            for (int poll = 0; poll < 10 && !isCompleteStatus(status) && !isFailureStatus(status); poll++) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                String latest = adapter.getOrderStatus(account.getAccessToken(), closeResp.orderId());
                if (latest != null && !latest.isBlank() && !"UNKNOWN".equalsIgnoreCase(latest)) status = latest;
            }
            if (isCompleteStatus(status)) {
                addLog("EXEC", "SQUARE_OFF", legKey + " " + symbol + " -> " + closeSide + " CONFIRMED FILLED");
                return true;
            }
            addLog("EXEC", "SQUARE_OFF_UNCONFIRMED", legKey + " " + symbol + " attempt " + attempt + " ended status=" + status);
        }
        log.error("CRITICAL: square-off for {} {} did NOT confirm a fill after 2 attempts -- naked exposure, needs manual intervention", legKey, symbol);
        addLog("EXEC", "SQUARE_OFF_CRITICAL", legKey + " " + symbol + " -- NOT CONFIRMED CLOSED after 2 attempts. MANUAL INTERVENTION NEEDED.");
        return false;
    }

    private void cancelPendingLegs(BrokerAccount account, BrokerAdapter adapter, List<PlacedLeg> legs) {
        for (PlacedLeg leg : legs) {
            if (leg.orderId == null || leg.orderId.isBlank() || isCompleteStatus(leg.status)) {
                continue;
            }
            try {
                adapter.cancelOrder(account.getAccessToken(), leg.orderId);
            } catch (Exception e) {
                log.warn("Failed to cancel {} {}: {}", leg.leg.legKey(), leg.orderId, e.getMessage());
            }
        }
    }

    /**
     * @return true only if every leg's closing order was confirmed FILLED, not just accepted.
     * Deliberately attempts to close every leg passed in regardless of its last-known status
     * (callers sometimes pass legs whose status hasn't been polled to a final value yet) --
     * closing an order that never actually filled just gets rejected by the broker as "no
     * position", which is harmless; skipping a leg that WAS filled because of a stale status
     * read would leave real naked exposure, which is not.
     */
    private boolean squareOffFilledLegs(BrokerAccount account, BrokerAdapter adapter, List<PlacedLeg> legs) {
        boolean allConfirmed = true;
        for (PlacedLeg leg : legs) {
            BrokerOrderRequest.Side closeSide = leg.leg.side() == BrokerOrderRequest.Side.BUY
                    ? BrokerOrderRequest.Side.SELL
                    : BrokerOrderRequest.Side.BUY;
            allConfirmed &= placeAndVerifyCloseOrder(account, adapter, leg.leg.symbol(), closeSide, leg.leg.quantity(), leg.leg.legKey());
        }
        return allConfirmed;
    }

    private boolean isCompleteStatus(String status) {
        return "COMPLETE".equalsIgnoreCase(status) || "TRADED".equalsIgnoreCase(status);
    }

    private boolean isFailureStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        return normalized.contains("REJECT")
                || normalized.contains("CANCEL")
                || normalized.contains("EXPIRE")
                || normalized.contains("FAIL");
    }

    private double estimateEntryCost(OptionArbOpportunity opp, int lots) {
        int lotSize = getLotSize(opp.getUnderlying());
        double ce = opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0;
        double pe = opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0;
        double fut = opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0;
        return (ce + pe + fut) * lotSize * lots;
    }

    private double estimateHedgedMargin(String underlying, int lots) {
        // Hedged margin for box spread (FUT + CE + PE) — margin is the max of individual legs, not sum
        // With SEBI SPAN+Exposure, hedged NFO positions get ~60-70% margin benefit
        // Fallback: use controller-consistent estimates when NAVIA API unavailable
        Map<String, Double> baseMargins = Map.of(
            "NIFTY", 150000.0,
            "BANKNIFTY", 250000.0,
            "MIDCPNIFTY", 180000.0,
            "FINNIFTY", 200000.0
        );
        double base = baseMargins.getOrDefault(underlying, 200000.0);
        return base * lots * 1.15; // 15% buffer for slippage
    }

    /** This used to be its own hardcoded switch (NIFTY=25, BANKNIFTY=15, ...) completely
     *  independent of OptionChainService's dynamic Zerodha-fetched lot sizes -- so fixing the
     *  dynamic fetch earlier never touched THIS copy, and every live order quantity computed
     *  here kept using the old stale numbers. Concrete symptom: a BANKNIFTY order rejected by
     *  Zerodha with "quantity should be multiple of 30" because qty was computed from
     *  lotSize=15 here while the real, current lot size is 30. Delegate to the single source
     *  of truth instead of maintaining a second copy that silently drifts out of sync.
     */
    private int getLotSize(String underlying) {
        return optionChainService.getLotSize(underlying);
    }

    private double recalculateTargetEdge(double ceEntry, double peEntry, double futEntry, int strike, String action, String underlying) {
        return recalculateTargetEdge(ceEntry, peEntry, futEntry, strike, action, underlying, 0);
    }

    private double recalculateTargetEdge(double ceEntry, double peEntry, double futEntry, int strike, String action, String underlying, double daysToExpiry) {
        if (ceEntry <= 0 || peEntry <= 0 || futEntry <= 0) return 0;

        int lotSize = getLotSize(underlying);
        double discountedStrike = ArbitrageCosts.discountedStrike(strike, daysToExpiry);
        double synthetic = ceEntry - peEntry + discountedStrike;
        double parityDev = Math.abs(futEntry - synthetic);
        double grossEdge = parityDev * lotSize;

        return ArbitrageCosts.netEdge(ceEntry, peEntry, futEntry, lotSize, grossEdge, action);
    }

    private double computePnl(LivePosition pos, Map<String, OptionChainService.OptionQuote> quotes) {
        double ceBid = 0, ceAsk = 0, peBid = 0, peAsk = 0, futBid = 0, futAsk = 0;
        double ceCurrent = 0, peCurrent = 0, futCurrent = 0;

        if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) {
            OptionChainService.OptionQuote q = quotes.get(pos.getCeSymbol());
            ceCurrent = q.lastPrice; ceBid = q.bid > 0 ? q.bid : q.lastPrice; ceAsk = q.ask > 0 ? q.ask : q.lastPrice;
        }
        if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) {
            OptionChainService.OptionQuote q = quotes.get(pos.getPeSymbol());
            peCurrent = q.lastPrice; peBid = q.bid > 0 ? q.bid : q.lastPrice; peAsk = q.ask > 0 ? q.ask : q.lastPrice;
        }
        if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) {
            OptionChainService.OptionQuote q = quotes.get(pos.getFutSymbol());
            futCurrent = q.lastPrice; futBid = q.bid > 0 ? q.bid : q.lastPrice; futAsk = q.ask > 0 ? q.ask : q.lastPrice;
        }

        double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
        double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
        double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
        int lotSize = pos.getLotSize() != null ? pos.getLotSize() : getLotSize(pos.getUnderlying());
        int lots = pos.getLots() != null ? pos.getLots() : 1;
        String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
        double pnl = 0;

        if (ceCurrent > 0 || peCurrent > 0 || futCurrent > 0) {
            if (action.contains("BUY CE +")) {
                if (ceCurrent > 0 && ceEntry > 0) pnl += ceBid - ceEntry;
                if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peAsk;
                if (futCurrent > 0 && futEntry > 0) pnl += futBid > 0 ? (futEntry > futCurrent ? (futEntry - futAsk) : (futBid - futEntry)) : (futCurrent - futEntry);
                // Actually, if it's BUY CE + SELL PE + SELL FUT, we sold fut. Exit = buy fut at ask
                if (action.contains("SELL FUT")) {
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futAsk;
                } else if (action.contains("BUY FUT")) {
                    if (futCurrent > 0 && futEntry > 0) pnl += futBid - futEntry;
                }
            } else if (action.contains("SELL CE +")) {
                if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceAsk;
                if (peCurrent > 0 && peEntry > 0) pnl += peBid - peEntry;
                if (action.contains("SELL FUT")) {
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futAsk;
                } else if (action.contains("BUY FUT")) {
                    if (futCurrent > 0 && futEntry > 0) pnl += futBid - futEntry;
                } else {
                    if (futCurrent > 0 && futEntry > 0) pnl += futBid - futEntry; // Fallback
                }
            } else {
                if (ceCurrent > 0 && ceEntry > 0) pnl += ceBid - ceEntry;
                if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peAsk;
                if (futCurrent > 0 && futEntry > 0) pnl += futBid - futEntry;
            }
        }
        return pnl * lotSize * lots;
    }

    public void addLog(String type, String status, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", System.currentTimeMillis());
        entry.put("time", LocalTime.now(ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("type", type);
        entry.put("status", status);
        entry.put("message", message);
        execLogs.add(entry);
        if (execLogs.size() > 200) execLogs.remove(0);
    }

    private record PlannedLeg(String symbol, BrokerOrderRequest.Side side, int quantity, double price, String legKey) {}

    private static final class PlacedLeg {
        private final PlannedLeg leg;
        private final String orderId;
        private String status;

        private PlacedLeg(PlannedLeg leg, String orderId, String status) {
            this.leg = leg;
            this.orderId = orderId;
            this.status = status;
        }
    }

    // --- MULTI-TENANT OVERLOAD ---
    public Map<String, Object> manualExecuteLive(OptionArbOpportunity opp, int lots, String broker, Long paramUserId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (opp == null) {
            result.put("status", "ERROR");
            result.put("message", "Opportunity not found");
            return result;
        }
        
        long openCount = positionRepo.countOpenLive();
        int maxOpen = ((Number) getSettings().getOrDefault("maxOpenPositions", 1)).intValue();
        if (openCount >= maxOpen) {
            result.put("status", "ERROR");
            result.put("message", "Already have " + openCount + "/" + maxOpen + " open positions.");
            return result;
        }

        com.stokr.broker.BrokerAccount account;
        com.stokr.broker.BrokerAdapter adapter;
        try {
            java.util.List<com.stokr.broker.BrokerAccount> accounts = brokerAccountRepo.findByUserIdAndBrokerNameAndStatus(paramUserId, broker, "ACTIVE");
            if (accounts.isEmpty()) { result.put("status", "ERROR"); result.put("message", "No " + broker + " account found for user"); return result; }
            account = accounts.get(0);
            adapter = brokerService.getAdapter(broker);
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Broker setup failed: " + e.getMessage());
            return result;
        }

        java.util.List<Map<String, Object>> legs = opp.getLegList();
        boolean isMultiLeg = legs != null && !legs.isEmpty();

        boolean opened = isMultiLeg
                ? executeMultiLegTrade(account, adapter, opp, lots, paramUserId, legs)
                : executeTrade(account, adapter, opp, lots, paramUserId);

        if (opened) {
            result.put("status", "SUCCESS");
            result.put("underlying", opp.getUnderlying());
            result.put("strike", opp.getStrike());
            result.put("action", opp.getAction());
            result.put("message", opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction() + " entered LIVE via " + broker);
        } else {
            String detail = positionRepo.findByOpportunityIdIn(java.util.List.of(opp.getId())).stream()
                    .filter(p -> p.getErrorMessage() != null)
                    .reduce((first, second) -> second)
                    .map(LivePosition::getErrorMessage)
                    .orElse(null);
            result.put("status", "ERROR");
            result.put("message", detail != null ? detail : "Live order failed");
        }
        return result;
    }

}
