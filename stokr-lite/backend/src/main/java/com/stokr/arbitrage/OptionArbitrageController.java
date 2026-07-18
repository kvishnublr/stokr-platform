package com.stokr.arbitrage;

import com.stokr.broker.BrokerOrderRequest;
import com.stokr.broker.BrokerPosition;
import com.stokr.broker.ZerodhaAdapter;
import com.stokr.external.ZerodhaTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/option-arbitrage")
public class OptionArbitrageController {

    private static final Logger log = LoggerFactory.getLogger(OptionArbitrageController.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbHistoryService historyService;
    private final CalendarSpreadService calendarSpreadService;
    private final VolSurfaceService volSurfaceService;
    private final OptionArbAutoExecuteService autoExecService;
    private final OptionArbExecutionService executionService;
    private final ExecutedTradeRepository tradeRepo;
    private final ZerodhaAdapter zerodhaAdapter;
    private final ZerodhaTokenManager tokenManager;

    private final ConcurrentHashMap<String, List<ArbitrageOpportunity>> scanCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> scanTimestamp = new ConcurrentHashMap<>();

    private static final double RISK_FREE_RATE = 0.065;
    private static final Set<String> ALL_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY");

    public OptionArbitrageController(OptionChainService optionChainService,
                                      ZerodhaSpotPriceFetcher spotFetcher,
                                      OptionArbHistoryService historyService,
                                      CalendarSpreadService calendarSpreadService,
                                      VolSurfaceService volSurfaceService,
                                      OptionArbAutoExecuteService autoExecService,
                                      OptionArbExecutionService executionService,
                                      ExecutedTradeRepository tradeRepo,
                                      ZerodhaAdapter zerodhaAdapter,
                                      ZerodhaTokenManager tokenManager) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.historyService = historyService;
        this.calendarSpreadService = calendarSpreadService;
        this.volSurfaceService = volSurfaceService;
        this.autoExecService = autoExecService;
        this.executionService = executionService;
        this.tradeRepo = tradeRepo;
        this.zerodhaAdapter = zerodhaAdapter;
        this.tokenManager = tokenManager;
    }

    private record UnderlyingConfig(String name, String spotKey, String futuresPrefix) {}

    private static final Map<String, UnderlyingConfig> CONFIGS = Map.of(
        "NIFTY", new UnderlyingConfig("NIFTY", "NSE:NIFTY 50", "NFO:NIFTY"),
        "BANKNIFTY", new UnderlyingConfig("BANKNIFTY", "NSE:NIFTY BANK", "NFO:BANKNIFTY"),
        "MIDCPNIFTY", new UnderlyingConfig("MIDCPNIFTY", "NSE:NIFTY MID SELECT", "NFO:MIDCPNIFTY"),
        "FINNIFTY", new UnderlyingConfig("FINNIFTY", "NSE:NIFTY FIN SERVICE", "NFO:FINNIFTY")
    );

    private double getValidatedFutures(String underlying, double spot) {
        try {
            UnderlyingConfig cfg = CONFIGS.get(underlying);
            if (cfg == null) return spot;
            LocalDate expiry = optionChainService.getMonthlyExpiry();
            int yy = expiry.getYear() % 100;
            String mon = expiry.getMonth().name().substring(0, 3);
            String futKey = cfg.futuresPrefix() + String.format("%02d%sFUT", yy, mon);
            double futLtp = spotFetcher.getSpotPrice(futKey);
            if (futLtp > 0) return futLtp;
        } catch (Exception e) {
            log.debug("Futures fetch failed for {}: {}", underlying, e.getMessage());
        }
        return spot;
    }

    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "false") boolean debug) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());

        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            resp.put("marketClosed", true);
            resp.put("opportunities", Collections.emptyList());
            resp.put("count", 0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", 0);
            summary.put("parityBreaks", 0);
            resp.put("summary", summary);
            return ResponseEntity.ok(resp);
        }

        List<ArbitrageOpportunity> allOpps = new ArrayList<>();
        Set<String> underlyings = "ALL".equals(underlying) ? ALL_UNDERLYINGS : Set.of(underlying);

        for (String u : underlyings) {
            try {
                UnderlyingConfig cfg = CONFIGS.get(u);
                double spot = cfg != null ? spotFetcher.getSpotPrice(cfg.spotKey()) : 0;
                double fut = getValidatedFutures(u, spot);
                List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(u, spot, fut, true, debug);
                allOpps.addAll(opps);
            } catch (Exception e) {
                log.error("Scan failed for {}: {}", u, e.getMessage());
            }
        }

        allOpps.sort((a, b) -> Double.compare(b.edgeAfterCosts, a.edgeAfterCosts));
        resp.put("opportunities", allOpps.stream().map(ArbitrageOpportunity::toMap).toList());
        resp.put("count", allOpps.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", allOpps.size());
        long parityBreaks = allOpps.stream().filter(o -> "PARITY_BREAK".equals(o.type)).count();
        summary.put("parityBreaks", parityBreaks);
        resp.put("summary", summary);

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());

        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<OptionArbOpportunity> dbOpps;
            if ("ALL".equals(underlying)) {
                dbOpps = historyService.getTodayOpportunities(today);
            } else {
                dbOpps = historyService.getTodayOpportunities(today, underlying);
            }

            List<Map<String, Object>> opps = new ArrayList<>();
            for (OptionArbOpportunity dbOpp : dbOpps) {
                Map<String, Object> m = dbOpp.toMap();

                if ("PARITY_BREAK".equals(dbOpp.getType())) {
                    double spot = dbOpp.getSpotPrice() != null ? dbOpp.getSpotPrice().doubleValue() : 0;
                    double futPrice = dbOpp.getFuturesPrice() != null ? dbOpp.getFuturesPrice().doubleValue() : 0;
                    double ceEntry = dbOpp.getCeEntryPrice() != null ? dbOpp.getCeEntryPrice().doubleValue() : 0;
                    double peEntry = dbOpp.getPeEntryPrice() != null ? dbOpp.getPeEntryPrice().doubleValue() : 0;
                    int lotSize = OptionChainService.getLotSize(dbOpp.getUnderlying());

                    double grossEdge = (dbOpp.getEdgePoints() != null ? dbOpp.getEdgePoints().doubleValue() : 0) * lotSize;
                    Map<String, Double> costs = optionChainService.calculateCostBreakdown(
                        dbOpp.getEdgePoints() != null ? dbOpp.getEdgePoints().doubleValue() : 0, dbOpp.getUnderlying());
                    m.put("costBreakdown", costs);
                    m.put("edgeAfterCosts", costs.get("netEdge"));

                    if ("CONVERSION".equals(dbOpp.getAction())) {
                        m.put("maxProfit", costs.get("netEdge"));
                        m.put("maxLoss", 0.0);
                    } else {
                        m.put("maxProfit", 0.0);
                        m.put("maxLoss", costs.get("netEdge") != null ? -costs.get("netEdge") : 0.0);
                    }
                }

                opps.add(m);
            }

            resp.put("opportunities", opps);
            resp.put("count", opps.size());
        } catch (Exception e) {
            log.error("Failed to fetch today's opportunities: {}", e.getMessage());
            resp.put("opportunities", Collections.emptyList());
            resp.put("count", 0);
        }

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/opportunities")
    public ResponseEntity<Map<String, Object>> cachedOpportunities(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<ArbitrageOpportunity> opps = scanCache.getOrDefault(underlying, Collections.emptyList());
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/calendar-spread")
    public ResponseEntity<Map<String, Object>> calendarSpread(
            @RequestParam String underlying,
            @RequestParam(defaultValue = "0") int strike) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            UnderlyingConfig cfg = CONFIGS.get(underlying);
            double spot = cfg != null ? spotFetcher.getSpotPrice(cfg.spotKey()) : 0;
            double fut = getValidatedFutures(underlying, spot);
            var result = calendarSpreadService.scanCalendarSpreads(underlying, spot, fut);
            resp.put("opportunities", result);
            resp.put("count", result.size());
        } catch (Exception e) {
            log.error("Calendar spread failed: {}", e.getMessage());
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/vol-surface")
    public ResponseEntity<Map<String, Object>> volSurface(@RequestParam String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            UnderlyingConfig cfg = CONFIGS.get(underlying);
            double spot = cfg != null ? spotFetcher.getSpotPrice(cfg.spotKey()) : 0;
            double fut = getValidatedFutures(underlying, spot);
            var result = volSurfaceService.getVolSurface(underlying, spot, fut);
            resp.put("surface", result);
        } catch (Exception e) {
            log.error("Vol surface failed: {}", e.getMessage());
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/live-prices")
    public ResponseEntity<Map<String, Object>> livePrices(
            @RequestParam String underlying,
            @RequestParam int strike) {
        Map<String, Object> resp = new LinkedHashMap<>();

        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            resp.put("marketClosed", true);
            return ResponseEntity.ok(resp);
        }

        try {
            UnderlyingConfig cfg = CONFIGS.get(underlying);
            if (cfg == null) {
                resp.put("error", "Unknown underlying: " + underlying);
                return ResponseEntity.ok(resp);
            }

            LocalDate expiry = optionChainService.getMonthlyExpiry();
            int yy = expiry.getYear() % 100;
            String mon = expiry.getMonth().name().substring(0, 3);
            String prefix = cfg.futuresPrefix().replace("NFO:", "");

            String ceKey = String.format("NFO:%s%02d%s%dCE", prefix, yy, mon, strike);
            String peKey = String.format("NFO:%s%02d%s%dPE", prefix, yy, mon, strike);
            String futKey = cfg.futuresPrefix() + String.format("%02d%sFUT", yy, mon);

            resp.put("spotLive", spotFetcher.getSpotPrice(cfg.spotKey()));
            resp.put("ceLive", spotFetcher.getSpotPrice(ceKey));
            resp.put("peLive", spotFetcher.getSpotPrice(peKey));
            resp.put("futLive", spotFetcher.getSpotPrice(futKey));
        } catch (Exception e) {
            log.debug("Live prices failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/live-prices-batch")
    public ResponseEntity<Map<String, Object>> livePricesBatch(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();

        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            resp.put("marketClosed", true);
            return ResponseEntity.ok(resp);
        }

        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<OptionArbOpportunity> dbOpps;
            if ("ALL".equals(underlying)) {
                dbOpps = historyService.getTodayOpportunities(today);
            } else {
                dbOpps = historyService.getTodayOpportunities(today, underlying);
            }

            if (dbOpps.isEmpty()) {
                resp.put("prices", Collections.emptyMap());
                return ResponseEntity.ok(resp);
            }

            LocalDate expiry = optionChainService.getMonthlyExpiry();
            int yy = expiry.getYear() % 100;
            String monStr = expiry.getMonth().name().substring(0, 3);

            Set<String> instrumentSet = new LinkedHashSet<>();
            Map<String, List<OptionArbOpportunity>> spotKeyToOpps = new LinkedHashMap<>();
            Map<String, String> oppKeyToInstrument = new HashMap<>();

            for (OptionArbOpportunity dbOpp : dbOpps) {
                if (!"PARITY_BREAK".equals(dbOpp.getType())) continue;
                UnderlyingConfig cfg = CONFIGS.get(dbOpp.getUnderlying());
                if (cfg == null) continue;

                String prefix = cfg.futuresPrefix().replace("NFO:", "");
                int strike = (int) dbOpp.getStrike().doubleValue();
                String ceSymbol = String.format("%s%02d%s%dCE", prefix, yy, monStr, strike);
                String peSymbol = String.format("%s%02d%s%dPE", prefix, yy, monStr, strike);
                String futSymbol = String.format("%s%02d%sFUT", prefix, yy, monStr);

                instrumentSet.add(ceSymbol);
                instrumentSet.add(peSymbol);
                instrumentSet.add(futSymbol);

                String spotKey = cfg.spotKey();
                spotKeyToOpps.computeIfAbsent(spotKey, k -> new ArrayList<>()).add(dbOpp);

                oppKeyToInstrument.put(dbOpp.getId() + "_ce", ceSymbol);
                oppKeyToInstrument.put(dbOpp.getId() + "_pe", peSymbol);
                oppKeyToInstrument.put(dbOpp.getId() + "_fut", futSymbol);
            }

            Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(new ArrayList<>(instrumentSet));

            Map<String, Double> spotCache = new HashMap<>();
            for (String spotKey : spotKeyToOpps.keySet()) {
                spotCache.put(spotKey, spotFetcher.getSpotPrice(spotKey));
            }

            Map<String, Object> prices = new HashMap<>();
            for (OptionArbOpportunity dbOpp : dbOpps) {
                if (!"PARITY_BREAK".equals(dbOpp.getType())) continue;
                UnderlyingConfig cfg = CONFIGS.get(dbOpp.getUnderlying());
                if (cfg == null) continue;

                String idKey = dbOpp.getId() + "";
                String ceSymbol = oppKeyToInstrument.get(idKey + "_ce");
                String peSymbol = oppKeyToInstrument.get(idKey + "_pe");
                String futSymbol = oppKeyToInstrument.get(idKey + "_fut");

                OptionChainService.OptionQuote ceQ = quotes.get(ceSymbol);
                OptionChainService.OptionQuote peQ = quotes.get(peSymbol);
                OptionChainService.OptionQuote futQ = quotes.get(futSymbol);

                Map<String, Object> lp = new HashMap<>();
                lp.put("spotLive", spotCache.getOrDefault(cfg.spotKey(), 0.0));
                lp.put("ceLive", ceQ != null ? ceQ.lastPrice : 0.0);
                lp.put("peLive", peQ != null ? peQ.lastPrice : 0.0);
                lp.put("futLive", futQ != null ? futQ.lastPrice : 0.0);
                prices.put(idKey, lp);
                prices.put(dbOpp.getUnderlying() + "_" + (int)dbOpp.getStrike().doubleValue(), lp);
            }

            resp.put("prices", prices);
        } catch (Exception e) {
            log.error("Batch live prices failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String date) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            if (date != null && !date.isEmpty()) {
                LocalDate d = LocalDate.parse(date);
                var result = historyService.getHistoryByDatePage(d, PageRequest.of(page, size));
                resp.put("opportunities", result.getContent().stream().map(OptionArbOpportunity::toMap).toList());
                resp.put("totalElements", result.getTotalElements());
                resp.put("totalPages", result.getTotalPages());
                resp.put("currentPage", page);
            } else {
                var result = historyService.getAllHistory(PageRequest.of(page, size));
                resp.put("opportunities", result.getContent().stream().map(OptionArbOpportunity::toMap).toList());
                resp.put("totalElements", result.getTotalElements());
                resp.put("totalPages", result.getTotalPages());
                resp.put("currentPage", page);
            }
        } catch (Exception e) {
            log.error("History query failed: {}", e.getMessage());
            resp.put("opportunities", Collections.emptyList());
            resp.put("totalElements", 0);
            resp.put("totalPages", 0);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history/past")
    public ResponseEntity<Map<String, Object>> historyPast(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            var result = historyService.getHistoryExcludingToday(PageRequest.of(page, size));
            resp.put("opportunities", result.getContent().stream().map(OptionArbOpportunity::toMap).toList());
            resp.put("totalElements", result.getTotalElements());
            resp.put("totalPages", result.getTotalPages());
            resp.put("currentPage", page);
        } catch (Exception e) {
            log.error("Past history query failed: {}", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history/summary")
    public ResponseEntity<Map<String, Object>> historySummary(
            @RequestParam(required = false) String date) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            LocalDate d = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : null;
            var summary = historyService.getSummary(d);
            resp.put("totalOpportunities", summary.getTotalOpportunities());
            resp.put("totalEdgeDetected", summary.getTotalEdgeDetected());
            resp.put("totalPnlAfterCosts", summary.getTotalPnlAfterCosts());
            resp.put("winRate", summary.getWinRate());
            resp.put("wins", summary.getWins());
            resp.put("losses", summary.getLosses());
        } catch (Exception e) {
            log.error("Summary query failed: {}", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history/dates")
    public ResponseEntity<Map<String, Object>> historyDates(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            var dates = historyService.getAvailableDates(days);
            resp.put("dates", dates);
        } catch (Exception e) {
            log.error("Dates query failed: {}", e.getMessage());
            resp.put("dates", Collections.emptyList());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scannerReady", true);
        resp.put("underlyings", List.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY"));
        resp.put("dteRanges", Map.of(
            "NIFTY", List.of(3, 7),
            "BANKNIFTY", List.of(3, 21),
            "MIDCPNIFTY", List.of(3, 21),
            "FINNIFTY", List.of(3, 21)
        ));
        resp.put("settings", Map.of(
            "minParityDeviation", 15,
            "minEdgeAfterCosts", 200,
            "maxSpreadPct", 2.0,
            "riskFreeRate", 6.5
        ));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/auto-execute/settings")
    public ResponseEntity<Map<String, Object>> getAutoExecSettings() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", autoExecService.isAutoExecEnabled());
        resp.put("settings", autoExecService.getAllSettings());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/settings")
    public ResponseEntity<Map<String, Object>> updateAutoExecSetting(
            @RequestParam String key,
            @RequestParam String value) {
        autoExecService.setSetting(key, value);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "ok");
        resp.put("settings", autoExecService.getAllSettings());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/toggle")
    public ResponseEntity<Map<String, Object>> toggleAutoExec() {
        Map<String, Object> resp = new LinkedHashMap<>();
        boolean current = autoExecService.isAutoExecEnabled();
        autoExecService.setSetting("auto_execute_enabled", String.valueOf(!current));
        resp.put("enabled", !current);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/run")
    public ResponseEntity<Map<String, Object>> runAutoExecCycle() {
        Map<String, Object> resp = new LinkedHashMap<>();
        autoExecService.autoExecCycle();
        resp.put("message", "Auto-execute cycle triggered");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/auto-execute/trades")
    public ResponseEntity<Map<String, Object>> getAutoExecTrades(
            @RequestParam(defaultValue = "ALL") String status) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<ExecutedTrade> trades;
        if ("ALL".equals(status)) {
            trades = tradeRepo.findAll();
        } else {
            trades = tradeRepo.findByStatusOrderByExecutedAtDesc(status);
        }
        List<Map<String, Object>> tradeMaps = new ArrayList<>();
        for (ExecutedTrade t : trades) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("underlying", t.getUnderlying());
            m.put("strike", t.getStrike());
            m.put("action", t.getAction());
            m.put("ceSymbol", t.getCeSymbol());
            m.put("peSymbol", t.getPeSymbol());
            m.put("futSymbol", t.getFutSymbol());
            m.put("ceEntryPrice", t.getCeEntryPrice());
            m.put("peEntryPrice", t.getPeEntryPrice());
            m.put("futEntryPrice", t.getFutEntryPrice());
            m.put("ceOrderId", t.getCeOrderId());
            m.put("peOrderId", t.getPeOrderId());
            m.put("futOrderId", t.getFutOrderId());
            m.put("lotSize", t.getLotSize());
            m.put("status", t.getStatus());
            m.put("notes", t.getNotes());
            m.put("executedAt", t.getExecutedAt() != null ? t.getExecutedAt().toString() : null);
            m.put("closedAt", t.getClosedAt() != null ? t.getClosedAt().toString() : null);
            m.put("closeCeOrderId", t.getCloseCeOrderId());
            m.put("closePeOrderId", t.getClosePeOrderId());
            m.put("closeFutOrderId", t.getCloseFutOrderId());
            m.put("closeCePrice", t.getCloseCePrice());
            m.put("closePePrice", t.getClosePePrice());
            m.put("closeFutPrice", t.getCloseFutPrice());
            m.put("pnlPoints", t.getPnlPoints());
            m.put("pnlAmount", t.getPnlAmount());
            m.put("expiryDate", t.getExpiryDate() != null ? t.getExpiryDate().toString() : null);
            tradeMaps.add(m);
        }
        resp.put("trades", tradeMaps);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/close/{tradeId}")
    public ResponseEntity<Map<String, Object>> closeTrade(
            @PathVariable Long tradeId,
            @RequestParam(defaultValue = "all") String what) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<ExecutedTrade> opt = tradeRepo.findById(tradeId);
        if (opt.isEmpty()) {
            resp.put("error", "Trade not found");
            return ResponseEntity.ok(resp);
        }
        ExecutedTrade trade = opt.get();
        try {
            ExecutedTrade closed;
            if ("options".equals(what)) {
                closed = autoExecService.closeOptionsOnly(trade);
            } else {
                closed = autoExecService.closePosition(trade);
            }
            resp.put("status", "ok");
            resp.put("trade", closed);
        } catch (Exception e) {
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/close-all")
    public ResponseEntity<Map<String, Object>> closeAllTrades() {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<ExecutedTrade> open = tradeRepo.findAllOpen();
        int closed = 0;
        for (ExecutedTrade t : open) {
            try {
                autoExecService.closePosition(t);
                closed++;
            } catch (Exception e) {
                log.error("Failed to close trade {}: {}", t.getId(), e.getMessage());
            }
        }
        resp.put("closed", closed);
        resp.put("total", open.size());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/replace")
    public ResponseEntity<Map<String, Object>> replaceTrade(
            @RequestParam Long tradeId,
            @RequestParam String newAction,
            @RequestParam double newCePrice,
            @RequestParam double newPePrice,
            @RequestParam double newFutPrice,
            @RequestParam double newSpotPrice) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<ExecutedTrade> opt = tradeRepo.findById(tradeId);
        if (opt.isEmpty()) {
            resp.put("error", "Trade not found");
            return ResponseEntity.ok(resp);
        }
        ExecutedTrade existing = opt.get();
        try {
            ArbitrageOpportunity newOpp = new ArbitrageOpportunity();
            newOpp.underlying = existing.getUnderlying();
            newOpp.strike = existing.getStrike();
            newOpp.action = newAction;
            newOpp.type = "PARITY_BREAK";
            newOpp.cePrice = newCePrice;
            newOpp.pePrice = newPePrice;
            newOpp.futuresPrice = newFutPrice;
            newOpp.spotPrice = newSpotPrice;

            ExecutedTrade closed = autoExecService.closePosition(existing);
            ExecutedTrade entered = autoExecService.executeNew(newOpp);
            resp.put("status", "ok");
            resp.put("closedTradeId", closed.getId());
            resp.put("newTradeId", entered != null ? entered.getId() : null);
        } catch (Exception e) {
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/replace-options")
    public ResponseEntity<Map<String, Object>> replaceOptionsOnly(
            @RequestParam Long tradeId,
            @RequestParam String newAction,
            @RequestParam double newCePrice,
            @RequestParam double newPePrice) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Optional<ExecutedTrade> opt = tradeRepo.findById(tradeId);
        if (opt.isEmpty()) {
            resp.put("error", "Trade not found");
            return ResponseEntity.ok(resp);
        }
        ExecutedTrade existing = opt.get();
        try {
            ExecutedTrade rolled = autoExecService.closeOptionsOnly(existing);
            resp.put("status", "ok");
            resp.put("rolledTradeId", rolled.getId());
        } catch (Exception e) {
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-execute/execute")
    public ResponseEntity<Map<String, Object>> executeOpportunity(
            @RequestParam String underlying,
            @RequestParam int strike,
            @RequestParam String action,
            @RequestParam double cePrice,
            @RequestParam double pePrice,
            @RequestParam double futPrice,
            @RequestParam double spotPrice) {
        Map<String, Object> resp = new LinkedHashMap<>();
        int lotSize = OptionChainService.getLotSize(underlying);

        OptionArbExecutionService.ExecutionResult execResult = executionService.execute(
            underlying, strike, action, cePrice, pePrice, futPrice, spotPrice, lotSize);

        resp.put("success", execResult.isSuccess());
        resp.put("partialFill", execResult.isPartialFill());
        resp.put("action", execResult.getAction());
        resp.put("underlying", execResult.getUnderlying());
        resp.put("strike", execResult.getStrike());
        resp.put("error", execResult.getError());
        if (execResult.getMarginAvailable() != null) {
            resp.put("marginAvailable", execResult.getMarginAvailable().doubleValue());
        }
        if (execResult.getMarginRequired() != null) {
            resp.put("marginRequired", execResult.getMarginRequired().doubleValue());
        }

        List<Map<String, Object>> legs = new ArrayList<>();
        for (OptionArbExecutionService.LegResult leg : execResult.getLegs()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("symbol", leg.getSymbol());
            lm.put("side", leg.getSide());
            lm.put("orderId", leg.getOrderId());
            lm.put("status", leg.getStatus());
            lm.put("message", leg.getMessage());
            lm.put("requestedPrice", leg.getRequestedPrice());
            lm.put("fillPrice", leg.getFillPrice());
            lm.put("quantity", leg.getQuantity());
            legs.add(lm);
        }
        resp.put("legs", legs);

        ExecutedTrade trade = new ExecutedTrade();
        trade.setUnderlying(underlying);
        trade.setStrike(strike);
        trade.setAction(action);
        trade.setExpiryDate(optionChainService.getMonthlyExpiry());
        trade.setLotSize(lotSize);
        trade.setStatus(execResult.isSuccess() ? "OPEN" : "FAILED");

        for (OptionArbExecutionService.LegResult leg : execResult.getLegs()) {
            double fillPrice = leg.getFillPrice() > 0 ? leg.getFillPrice() : leg.getRequestedPrice();
            if ("BUY".equals(leg.getSide()) && leg.getSymbol() != null && leg.getSymbol().endsWith("CE")) {
                trade.setCeSymbol(leg.getSymbol());
                trade.setCeOrderId(leg.getOrderId());
                trade.setCeEntryPrice(fillPrice);
            } else if ("SELL".equals(leg.getSide()) && leg.getSymbol() != null && leg.getSymbol().endsWith("CE")) {
                trade.setCeSymbol(leg.getSymbol());
                trade.setCeOrderId(leg.getOrderId());
                trade.setCeEntryPrice(fillPrice);
            } else if (leg.getSymbol() != null && leg.getSymbol().endsWith("PE")) {
                trade.setPeSymbol(leg.getSymbol());
                trade.setPeOrderId(leg.getOrderId());
                trade.setPeEntryPrice(fillPrice);
            } else if (leg.getSymbol() != null && leg.getSymbol().endsWith("FUT")) {
                trade.setFutSymbol(leg.getSymbol());
                trade.setFutOrderId(leg.getOrderId());
                trade.setFutEntryPrice(fillPrice);
            }
        }

        trade.setNotes(execResult.isSuccess() ? "Manually executed" : execResult.getError());
        ExecutedTrade saved = tradeRepo.save(trade);
        resp.put("tradeId", saved.getId());
        resp.put("tradeStatus", saved.getStatus());

        return ResponseEntity.ok(resp);
    }

    @GetMapping("/positions")
    public ResponseEntity<Map<String, Object>> getPositions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
            if (auth == null || auth.getAccessToken() == null) {
                resp.put("positions", List.of());
                resp.put("count", 0);
                resp.put("totalPnl", 0);
                resp.put("error", "No auth token");
                return ResponseEntity.ok(resp);
            }
            List<BrokerPosition> all = zerodhaAdapter.getPositions(auth.getAccessToken());
            List<Map<String, Object>> nfoPositions = new ArrayList<>();
            BigDecimal totalPnl = BigDecimal.ZERO;
            for (BrokerPosition pos : all) {
                if (!"NFO".equals(pos.exchange())) continue;
                if (pos.quantity() == 0) continue;
                String symbol = pos.symbol();
                String instrumentType = "FUT";
                if (symbol.contains("CE")) instrumentType = "CE";
                else if (symbol.contains("PE")) instrumentType = "PE";

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tradingsymbol", symbol);
                m.put("exchange", pos.exchange());
                m.put("product", pos.productType());
                m.put("quantity", pos.quantity());
                m.put("avgPrice", pos.avgPrice().doubleValue());
                m.put("ltp", pos.lastPrice().doubleValue());
                m.put("pnl", pos.unrealizedPnl().doubleValue());
                m.put("mtm", pos.lastPrice().subtract(pos.avgPrice()).multiply(BigDecimal.valueOf(pos.quantity())).doubleValue());
                m.put("instrumentType", instrumentType);
                nfoPositions.add(m);
                totalPnl = totalPnl.add(pos.unrealizedPnl());
            }
            resp.put("positions", nfoPositions);
            resp.put("count", nfoPositions.size());
            resp.put("totalPnl", totalPnl.doubleValue());
        } catch (Exception e) {
            log.error("Failed to fetch positions: {}", e.getMessage());
            resp.put("positions", List.of());
            resp.put("count", 0);
            resp.put("totalPnl", 0);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/exit-position")
    public ResponseEntity<Map<String, Object>> exitPosition(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "NFO") String exchange,
            @RequestParam(defaultValue = "MIS") String product,
            @RequestParam int quantity,
            @RequestParam String transactionType) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            ZerodhaTokenManager.ZerodhaAuth auth = tokenManager.getCurrentAuth();
            if (auth == null || auth.getAccessToken() == null) {
                resp.put("error", "No auth token");
                return ResponseEntity.ok(resp);
            }
            BrokerOrderRequest request = BrokerOrderRequest.builder()
                .symbol(symbol)
                .exchange(exchange)
                .side("SELL".equalsIgnoreCase(transactionType) ? BrokerOrderRequest.Side.SELL : BrokerOrderRequest.Side.BUY)
                .orderType(BrokerOrderRequest.OrderType.MARKET)
                .quantity(quantity)
                .productType(product)
                .build();

            com.stokr.broker.BrokerOrderResponse result = zerodhaAdapter.placeOrder(auth.getAccessToken(), request);
            resp.put("status", "ok");
            resp.put("orderId", result.orderId());
            resp.put("statusText", result.status());
        } catch (Exception e) {
            log.error("Failed to exit position {}: {}", symbol, e.getMessage());
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }
}
