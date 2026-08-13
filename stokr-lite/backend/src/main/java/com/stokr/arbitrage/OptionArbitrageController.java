package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/option-arbitrage")
public class OptionArbitrageController {

    private static final Logger log = LoggerFactory.getLogger(OptionArbitrageController.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final BidParityService bidParityService;
    private final BoxSpreadService boxSpreadService;
    private final CalendarSpreadService calendarSpreadService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbAutoExecService autoExecService;
    private final LivePositionRepository livePositionRepo;
    private final OptionArbOpportunityRepository oppRepo;

    private final Map<String, Object> autoExecSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> auditLogs = Collections.synchronizedList(new ArrayList<>());

    public OptionArbitrageController(OptionChainService optionChainService,
                                     OptionArbHistoryService historyService,
                                     BidParityService bidParityService,
                                     BoxSpreadService boxSpreadService,
                                     CalendarSpreadService calendarSpreadService,
                                     ZerodhaSpotPriceFetcher spotFetcher,
                                     OptionArbAutoExecService autoExecService,
                                     LivePositionRepository livePositionRepo,
                                     OptionArbOpportunityRepository oppRepo) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.bidParityService = bidParityService;
        this.boxSpreadService = boxSpreadService;
        this.calendarSpreadService = calendarSpreadService;
        this.spotFetcher = spotFetcher;
        this.autoExecService = autoExecService;
        this.livePositionRepo = livePositionRepo;
        this.oppRepo = oppRepo;

        autoExecSettings.put("normalParityEnabled", true);
        autoExecSettings.put("normalEntryEdge", 150.0);
        autoExecSettings.put("normalExitEdge", 20.0);
        autoExecSettings.put("normalMaxSets", 5);

        autoExecSettings.put("bidParityEnabled", true);
        autoExecSettings.put("bidEntryEdge", 300.0);
        autoExecSettings.put("bidExitEdge", 50.0);
        autoExecSettings.put("bidMaxSets", 3);

        autoExecSettings.put("scanInterval", 1);
        autoExecSettings.put("maxDailyLoss", 5000.0);
        autoExecSettings.put("status", "IDLE");

        addAuditLog("SYSTEM", "INFO", "Option Arbitrage Engine initialized. Ready for scanning.");
    }

    private void addAuditLog(String type, String status, String message) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("id", System.currentTimeMillis());
        logEntry.put("time", LocalTime.now(ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        logEntry.put("type", type);
        logEntry.put("status", status);
        logEntry.put("message", message);
        auditLogs.add(logEntry);
        if (auditLogs.size() > 100) {
            auditLogs.remove(0);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean marketOpen = !nowIST.isBefore(LocalTime.of(9, 15)) && !nowIST.isAfter(LocalTime.of(15, 30));
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "scannerReady", true,
            "marketOpen", marketOpen,
            "currentTimeIST", nowIST.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
            "underlyings", List.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY"),
            "feature", "option-arbitrage"
        ));
    }

    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(defaultValue = "ALL") String underlying,
                                                    @RequestParam(defaultValue = "false") boolean force) {
        List<Map<String, Object>> opps = new ArrayList<>();

        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("timestamp", System.currentTimeMillis());
            resp.put("underlying", underlying);
            resp.put("marketClosed", true);
            resp.put("opportunities", opps);
            resp.put("count", 0);
            resp.put("summary", Map.of("total", 0));
            resp.put("disabled", true);
            resp.put("reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST.");
            return ResponseEntity.ok(resp);
        }

        List<Map<String, Object>> bidOpps = bidParityService.scanBidParity(underlying);
        if (bidOpps != null) opps.addAll(bidOpps);

        List<Map<String, Object>> boxOpps = boxSpreadService.scanBoxSpread(underlying);
        if (boxOpps != null) opps.addAll(boxOpps);

        if (!opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.debug("Auto-exec from scan failed: {}", e.getMessage()); }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        resp.put("summary", Map.of("total", opps.size()));
        resp.put("disabled", false);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/bid-parity/scan")
    public ResponseEntity<Map<String, Object>> scanBidParity(@RequestParam(defaultValue = "ALL") String underlying) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> opps = bidParityService.scanBidParity(underlying);
        if (opps != null && !opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.debug("Auto-exec from bid-parity scan failed: {}", e.getMessage()); }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/box-spread/scan")
    public ResponseEntity<Map<String, Object>> scanBoxSpread(@RequestParam(defaultValue = "ALL") String underlying) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> opps = boxSpreadService.scanBoxSpread(underlying);
        if (opps != null && !opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.debug("Auto-exec from box-spread scan failed: {}", e.getMessage()); }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/calendar/scan")
    public ResponseEntity<Map<String, Object>> scanCalendarSpread(
            @RequestParam(defaultValue = "ALL") String underlying) {
        // Disabled: CalendarSpreadService's "expected carry" benchmark has no basis in option
        // pricing theory (real time-value comes from vega/theta, not futures cost-of-carry),
        // so its reported "edge" does not represent a genuine arbitrage opportunity. Re-enable
        // only after the pricing model is rebuilt and the strategy is honestly relabeled.
        return ResponseEntity.ok(Map.of(
            "timestamp", System.currentTimeMillis(),
            "underlying", underlying,
            "disabled", true,
            "opportunities", Collections.emptyList(),
            "count", 0,
            "reason", "Calendar Spread is temporarily disabled pending a pricing model review."
        ));
    }

    @GetMapping("/iron-condor/scan")
    public ResponseEntity<Map<String, Object>> scanIronCondor(
            @RequestParam(defaultValue = "ALL") String underlying) {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);
        List<Map<String, Object>> allOpps = new ArrayList<>();
        for (String u : targets) {
            try {
                allOpps.addAll(scanIronCondorForUnderlying(u));
            } catch (Exception e) {
                log.error("Iron Condor scan error for {}: {}", u, e.getMessage());
            }
        }
        if (!allOpps.isEmpty()) {
            try {
                List<OptionArbOpportunity> saved = historyService.saveIronCondorOpportunities(allOpps);
                for (int i = 0; i < saved.size() && i < allOpps.size(); i++) {
                    allOpps.get(i).put("id", saved.get(i).getId());
                    allOpps.get(i).put("scanTime", saved.get(i).getScanTime().toString());
                    allOpps.get(i).put("status", "RUNNING");
                }
            } catch (Exception e) {
                log.error("Failed to save Iron Condor opportunities: {}", e.getMessage());
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", allOpps);
        resp.put("count", allOpps.size());
        if (!allOpps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(allOpps); } catch (Exception e) { log.debug("Auto-exec from iron-condor scan failed: {}", e.getMessage()); }
        }
        return ResponseEntity.ok(resp);
    }

    private List<Map<String, Object>> scanIronCondorForUnderlying(String underlying) {
        List<Map<String, Object>> results = new ArrayList<>();
        LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
        if (expiry == null) return results;

        double[] spotFut = null;
        try {
            Map<String, String> spotKeys = Map.of(
                "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
                "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
            );
            String spotKey = spotKeys.getOrDefault(underlying, "NSE:NIFTY 50");
            String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
            spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
        } catch (Exception e) { return results; }
        if (spotFut == null || spotFut[0] <= 0) return results;
        double spot = spotFut[0];
        int step = OptionChainService.getStrikeStep(underlying);
        int atmStrike = (int) (Math.round(spot / step) * step);
        int lotSize = OptionChainService.getLotSize(underlying);

        List<Integer> strikes = new ArrayList<>();
        for (int i = -4; i <= 4; i++) strikes.add(atmStrike + i * step);

        List<String> instruments = new ArrayList<>();
        for (int s : strikes) {
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, s, "CE"));
            instruments.add(optionChainService.buildNfoSymbol(underlying, expiry, s, "PE"));
        }
        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(instruments);

        for (int wingWidth = 1; wingWidth <= 3; wingWidth++) {
            int putSell = atmStrike - wingWidth * step;
            int callSell = atmStrike + wingWidth * step;
            int putBuy = putSell - step;
            int callBuy = callSell + step;

            String psKey = optionChainService.buildNfoSymbol(underlying, expiry, putSell, "PE");
            String pbKey = optionChainService.buildNfoSymbol(underlying, expiry, putBuy, "PE");
            String csKey = optionChainService.buildNfoSymbol(underlying, expiry, callSell, "CE");
            String cbKey = optionChainService.buildNfoSymbol(underlying, expiry, callBuy, "CE");

            OptionChainService.OptionQuote ps = quotes.get(psKey);
            OptionChainService.OptionQuote pb = quotes.get(pbKey);
            OptionChainService.OptionQuote cs = quotes.get(csKey);
            OptionChainService.OptionQuote cb = quotes.get(cbKey);
            if (ps == null || pb == null || cs == null || cb == null) continue;

            double psBid = ps.bid > 0 ? ps.bid : ps.lastPrice;
            double pbAsk = pb.ask > 0 ? pb.ask : pb.lastPrice;
            double csBid = cs.bid > 0 ? cs.bid : cs.lastPrice;
            double cbAsk = cb.ask > 0 ? cb.ask : cb.lastPrice;
            if (psBid <= 0 || pbAsk <= 0 || csBid <= 0 || cbAsk <= 0) continue;

            double credit = (psBid - pbAsk) + (csBid - cbAsk);
            double maxLoss = (double) step - credit;
            if (maxLoss <= 0) continue;
            double riskReward = credit / maxLoss;
            double netEdge = credit * lotSize - 200.0;

            if (riskReward >= 0.2) {
                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("type", "IRON_CONDOR");
                opp.put("underlying", underlying);
                opp.put("strike", putSell);
                opp.put("action", "SELL " + putSell + "PE/" + callSell + "CE | BUY " + putBuy + "PE/" + callBuy + "CE");
                opp.put("legs", String.format("SELL %d PE @ %.1f | BUY %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d CE @ %.1f",
                    putSell, psBid, putBuy, pbAsk, callSell, csBid, callBuy, cbAsk));
                opp.put("credit", Math.round(credit * 100.0) / 100.0);
                opp.put("maxLoss", Math.round(maxLoss * 100.0) / 100.0);
                opp.put("riskReward", Math.round(riskReward * 100.0) / 100.0);
                opp.put("edgeAfterCosts", Math.round(netEdge * 10.0) / 10.0);
                opp.put("expiry", expiry.toString());
                opp.put("lotSize", lotSize);
                opp.put("spotPrice", spot);
                opp.put("wingWidth", wingWidth * step);
                opp.put("confidence", Math.min(95, 60 + riskReward * 100));
                results.add(opp);
            }
        }
        return results;
    }

    @GetMapping("/cash-surge/scan")
    public ResponseEntity<Map<String, Object>> scanCashSurge(
            @RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("opportunities", Collections.emptyList());
        resp.put("count", 0);
        resp.put("message", "Cash surge scanner requires stock delivery data feed. Not available via NFO options.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/cash-momentum/scan")
    public ResponseEntity<Map<String, Object>> scanCashMomentum(
            @RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("opportunities", Collections.emptyList());
        resp.put("count", 0);
        resp.put("message", "Cash momentum scanner is a stub. Implement with real stock data feed.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/signals")
    public ResponseEntity<Map<String, Object>> getSignals(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(defaultValue = "0") double minEdge,
            @RequestParam(required = false) Double maxEdge,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int days) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            ZoneId ist = ZoneId.of("Asia/Kolkata");
            LocalDate today = LocalDate.now(ist);

            LocalDate start;
            LocalDate end;

            if (startDate != null && !startDate.isEmpty()) {
                start = LocalDate.parse(startDate);
            } else {
                start = today.minusDays(days > 1 ? days - 1 : 0);
            }
            if (endDate != null && !endDate.isEmpty()) {
                end = LocalDate.parse(endDate);
            } else {
                end = today;
            }

            LocalDateTime startDateTime = start.atStartOfDay();
            LocalDateTime endDateTime = end.atTime(LocalTime.MAX);

            List<OptionArbOpportunity> allOpps;
            if ("ALL".equals(underlying)) {
                allOpps = historyService.getRepository()
                    .findByScanTimeBetween(startDateTime, endDateTime);
            } else {
                allOpps = historyService.getRepository()
                    .findByScanTimeBetweenAndUnderlyingOrderByScanTimeDesc(startDateTime, endDateTime, underlying);
            }

            List<OptionArbOpportunity> sortedOpps = allOpps.stream()
                    .sorted((a, b) -> {
                        LocalDateTime ta = a.getScanTime();
                        LocalDateTime tb = b.getScanTime();
                        if (ta == null && tb == null) return 0;
                        if (ta == null) return 1;
                        if (tb == null) return -1;
                        return tb.compareTo(ta);
                    })
                    .toList();

            List<Map<String, Object>> filtered = sortedOpps.stream()
                    .filter(o -> o.getEdgeAfterCosts() != null && o.getEdgeAfterCosts().doubleValue() >= minEdge)
                    .filter(o -> maxEdge == null || (o.getEdgeAfterCosts() != null && o.getEdgeAfterCosts().doubleValue() <= maxEdge))
                    .map(OptionArbOpportunity::toMap)
                    .toList();

            List<Map<String, Object>> limitedSignals = filtered.stream()
                    .limit(500)
                    .toList();

            long todayCount = allOpps.stream()
                    .filter(o -> o.getScanTime() != null && o.getScanTime().toLocalDate().equals(today))
                    .count();

            resp.put("signals", limitedSignals);
            resp.put("totalCount", filtered.size());
            resp.put("startDate", start.toString());
            resp.put("endDate", end.toString());
            resp.put("summary", Map.of("todayCount", todayCount));
        } catch (Exception e) {
            log.error("Failed to fetch signals: {}", e.getMessage());
            resp.put("signals", Collections.emptyList());
            resp.put("totalCount", 0);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(@RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<OptionArbOpportunity> opps = "ALL".equals(underlying)
                    ? historyService.getTodayOpportunities(today)
                    : historyService.getTodayOpportunities(today, underlying);
            resp.put("opportunities", opps.stream().map(OptionArbOpportunity::toMap).toList());
            resp.put("count", opps.size());
        } catch (Exception e) {
            log.error("Failed to fetch today's opportunities: {}", e.getMessage());
            resp.put("opportunities", Collections.emptyList());
            resp.put("count", 0);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size,
                                                       @RequestParam(required = false) String strategyType,
                                                       @RequestParam(required = false) String underlying,
                                                       @RequestParam(required = false) String startDate,
                                                       @RequestParam(required = false) String endDate) {
        Map<String, Object> resp = new LinkedHashMap<>();
        int cappedSize = Math.min(Math.max(size, 1), 50000);
        var result = historyService.getHistory(page, cappedSize, strategyType, underlying, startDate, endDate);
        resp.put("items", result.getContent().stream().map(OptionArbOpportunity::toMap).toList());
        resp.put("count", result.getNumberOfElements());
        resp.put("page", result.getNumber());
        resp.put("size", result.getSize());
        resp.put("totalElements", result.getTotalElements());
        resp.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history/summary")
    public ResponseEntity<Object> summary(@RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(historyService.getSummary(date));
    }

    @GetMapping("/history/dates")
    public ResponseEntity<Map<String, Object>> dates(@RequestParam(defaultValue = "30") int days) {
        List<LocalDate> dates = historyService.getAvailableDates(days);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("dates", dates);
        resp.put("count", dates.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/live-prices-batch")
    public ResponseEntity<Map<String, Object>> livePricesBatch(
            @RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<OptionArbOpportunity> runningOpps = historyService.getRepository()
                .findByStatusOrderByScanTimeBetween("RUNNING", today.atStartOfDay(), today.atTime(LocalTime.MAX));
            List<OptionArbOpportunity> openOpps = historyService.getRepository()
                .findByStatusOrderByScanTimeBetween("OPEN", today.atStartOfDay(), today.atTime(LocalTime.MAX));
            List<OptionArbOpportunity> detectedOpps = historyService.getRepository()
                .findByStatusOrderByScanTimeBetween("DETECTED", today.atStartOfDay(), today.atTime(LocalTime.MAX));

            List<OptionArbOpportunity> all = new ArrayList<>();
            all.addAll(runningOpps);
            all.addAll(openOpps);
            all.addAll(detectedOpps);

            if (!"ALL".equals(underlying)) {
                all = all.stream()
                    .filter(o -> underlying.equals(o.getUnderlying()))
                    .collect(java.util.stream.Collectors.toList());
            }

            List<Map<String, Object>> prices = new ArrayList<>();
            for (OptionArbOpportunity opp : all) {
                try {
                    if (opp.getExpiryDate() == null || opp.getStrike() == null) continue;
                    String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                    String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                    Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceSymbol, peSymbol));

                    double ceLive = 0, peLive = 0;
                    if (quotes.containsKey(ceSymbol) && quotes.get(ceSymbol).lastPrice > 0) ceLive = quotes.get(ceSymbol).lastPrice;
                    if (quotes.containsKey(peSymbol) && quotes.get(peSymbol).lastPrice > 0) peLive = quotes.get(peSymbol).lastPrice;

                    Map<String, String> spotKeyMap = Map.of(
                        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
                        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
                    );
                    String resolvedSpotKey = spotKeyMap.getOrDefault(opp.getUnderlying(), opp.getUnderlying());
                    double[] spotFut = spotFetcher.getSpotAndFutures(resolvedSpotKey, resolvedSpotKey);
                    double futLive = spotFut[1];

                    Map<String, Object> lp = new LinkedHashMap<>();
                    lp.put("underlying", opp.getUnderlying());
                    lp.put("strike", opp.getStrike());
                    lp.put("ceLive", ceLive);
                    lp.put("peLive", peLive);
                    lp.put("futLive", futLive);
                    lp.put("spotLive", spotFut[0]);
                    prices.add(lp);
                } catch (Exception e) {
                    log.debug("Live price fetch failed for {} {}: {}", opp.getUnderlying(), opp.getStrike(), e.getMessage());
                }
            }
            resp.put("prices", prices);
        } catch (Exception e) {
            log.error("Live prices batch failed: {}", e.getMessage());
            resp.put("prices", Collections.emptyList());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/history/live-pnl")
    public ResponseEntity<Map<String, Object>> livePnl(
            @RequestParam(required = false) String ids) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            Map<String, Object> pnlMap = new LinkedHashMap<>();
            Map<String, Object> statusMap = new LinkedHashMap<>();
            Map<String, Object> exitTimeMap = new LinkedHashMap<>();
            LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            boolean marketOpen = !nowIST.isBefore(LocalTime.of(9, 15)) && !nowIST.isAfter(LocalTime.of(15, 30));

            if (ids != null && !ids.isEmpty()) {
                List<Long> idList = Arrays.stream(ids.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::parseLong).collect(Collectors.toList());

                if (!idList.isEmpty()) {
                    List<LivePosition> positions = livePositionRepo.findByOpportunityIdIn(idList);
                    Map<Long, LivePosition> posByOpp = new LinkedHashMap<>();
                    for (LivePosition p : positions) {
                        if (p.getOpportunityId() != null) posByOpp.put(p.getOpportunityId(), p);
                    }

                    List<String> symbols = new ArrayList<>();
                    for (LivePosition p : positions) {
                        if (p.getCeSymbol() != null) symbols.add(p.getCeSymbol());
                        if (p.getPeSymbol() != null) symbols.add(p.getPeSymbol());
                        if (p.getFutSymbol() != null) symbols.add(p.getFutSymbol());
                    }

                    Map<String, OptionChainService.OptionQuote> quotes = Map.of();
                    try {
                        quotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
                    } catch (Exception e) {
                        log.debug("History live-pnl quote fetch failed: {}", e.getMessage());
                    }

                    // Untraded RUNNING bid-parity signals have no LivePosition — simulate
                    // mark-to-market P&L against live quotes and auto-exit once the edge target is hit.
                    List<Long> missingIds = idList.stream()
                        .filter(id -> !posByOpp.containsKey(id)).collect(Collectors.toList());
                    Map<Long, OptionArbOpportunity> missingOppMap = new LinkedHashMap<>();
                    if (!missingIds.isEmpty()) {
                        for (OptionArbOpportunity o : oppRepo.findAllById(missingIds)) {
                            missingOppMap.put(o.getId(), o);
                        }
                    }

                    Map<String, String> spotKeyMap = Map.of(
                        "NIFTY", "NSE:NIFTY 50", "BANKNIFTY", "NSE:NIFTY BANK",
                        "MIDCPNIFTY", "NSE:NIFTY MID SELECT", "FINNIFTY", "NSE:NIFTY FIN SERVICE"
                    );
                    Map<Long, String> ceSymByOpp = new LinkedHashMap<>();
                    Map<Long, String> peSymByOpp = new LinkedHashMap<>();
                    List<String> bpSymbols = new ArrayList<>();
                    for (OptionArbOpportunity o : missingOppMap.values()) {
                        if ("RUNNING".equals(o.getStatus()) && "BID_PARITY".equals(o.getStrategyType())
                                && o.getExpiryDate() != null && o.getStrike() != null) {
                            try {
                                String ceSym = optionChainService.buildNfoSymbol(o.getUnderlying(), o.getExpiryDate(), o.getStrike(), "CE");
                                String peSym = optionChainService.buildNfoSymbol(o.getUnderlying(), o.getExpiryDate(), o.getStrike(), "PE");
                                if (ceSym != null) { bpSymbols.add(ceSym); ceSymByOpp.put(o.getId(), ceSym); }
                                if (peSym != null) { bpSymbols.add(peSym); peSymByOpp.put(o.getId(), peSym); }
                            } catch (Exception ignored) {}
                        }
                    }
                    Map<String, OptionChainService.OptionQuote> bpQuotes = Map.of();
                    try {
                        bpQuotes = bpSymbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(bpSymbols);
                    } catch (Exception e) {
                        log.debug("Bid parity live-pnl quote fetch failed: {}", e.getMessage());
                    }
                    Map<String, double[]> futLiveByUnderlying = new LinkedHashMap<>();

                    for (Long oppId : idList) {
                        LivePosition pos = posByOpp.get(oppId);
                        String oppIdStr = String.valueOf(oppId);

                        if (pos != null) {
                            statusMap.put(oppIdStr, pos.getStatus());
                            if (pos.getExitedAt() != null) {
                                exitTimeMap.put(oppIdStr, pos.getExitedAt().toString());
                            } else if ("CLOSED".equals(pos.getStatus()) || "EXITED".equals(pos.getStatus())) {
                                exitTimeMap.put(oppIdStr, pos.getEnteredAt() != null ? pos.getEnteredAt().toString() : null);
                            }

                            // For CLOSED/EXITED positions, use stored P&L from DB
                            // If NULL (old positions), compute from exit prices
                            if ("CLOSED".equals(pos.getStatus()) || "EXITED".equals(pos.getStatus())) {
                                if (pos.getCurrentPnl() != null) {
                                    pnlMap.put(oppIdStr, Math.round(pos.getCurrentPnl().doubleValue()));
                                } else if (pos.getCeExitPrice() != null || pos.getPeExitPrice() != null || pos.getFutExitPrice() != null) {
                                    // Compute from exit prices
                                    double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
                                    double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
                                    double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
                                    double ceExit = pos.getCeExitPrice() != null ? pos.getCeExitPrice().doubleValue() : 0;
                                    double peExit = pos.getPeExitPrice() != null ? pos.getPeExitPrice().doubleValue() : 0;
                                    double futExit = pos.getFutExitPrice() != null ? pos.getFutExitPrice().doubleValue() : 0;
                                    int lotSz = pos.getLotSize() != null ? pos.getLotSize() : OptionChainService.getLotSize(pos.getUnderlying());
                                    int lotCount = pos.getLots() != null ? pos.getLots() : 1;
                                    double pnl = 0;
                                    String act = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
                                    if (act.contains("BUY CE +")) {
                                        if (ceExit > 0 && ceEntry > 0) pnl += ceExit - ceEntry;
                                        if (peExit > 0 && peEntry > 0) pnl += peEntry - peExit;
                                        if (futExit > 0 && futEntry > 0) pnl += futEntry - futExit;
                                    } else {
                                        if (ceExit > 0 && ceEntry > 0) pnl += ceEntry - ceExit;
                                        if (peExit > 0 && peEntry > 0) pnl += peExit - peEntry;
                                        if (futExit > 0 && futEntry > 0) pnl += futExit - futEntry;
                                    }
                                    pnl *= lotSz * lotCount;
                                    pnlMap.put(oppIdStr, Math.round(pnl));
                                    // Also persist so we don't recompute next time
                                    try {
                                        pos.setCurrentPnl(BigDecimal.valueOf(pnl));
                                        livePositionRepo.save(pos);
                                    } catch (Exception ignored) {}
                                } else {
                                    pnlMap.put(oppIdStr, 0);
                                }
                            } else {
                                double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
                                if (pos.getCeSymbol() != null && quotes.containsKey(pos.getCeSymbol())) ceCurrent = quotes.get(pos.getCeSymbol()).lastPrice;
                                if (pos.getPeSymbol() != null && quotes.containsKey(pos.getPeSymbol())) peCurrent = quotes.get(pos.getPeSymbol()).lastPrice;
                                if (pos.getFutSymbol() != null && quotes.containsKey(pos.getFutSymbol())) futCurrent = quotes.get(pos.getFutSymbol()).lastPrice;

                                double ceEntry = pos.getCeEntryPrice() != null ? pos.getCeEntryPrice().doubleValue() : 0;
                                double peEntry = pos.getPeEntryPrice() != null ? pos.getPeEntryPrice().doubleValue() : 0;
                                double futEntry = pos.getFutEntryPrice() != null ? pos.getFutEntryPrice().doubleValue() : 0;
                                int lotSize = pos.getLotSize() != null ? pos.getLotSize() : OptionChainService.getLotSize(pos.getUnderlying());
                                int lots = pos.getLots() != null ? pos.getLots() : 1;

                                double pnl = 0;
                                String action = pos.getAction() != null ? pos.getAction().toUpperCase() : "";
                                if (ceCurrent > 0 || peCurrent > 0 || futCurrent > 0) {
                                    if (action.contains("BUY CE +")) {
                                        if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                                        if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                                        if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                                    } else {
                                        if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                                        if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                                        if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
                                    }
                                }
                                pnl *= lotSize * lots;
                                pnlMap.put(oppIdStr, Math.round(pnl));
                            }
                        } else {
                            // No live_positions record — check opportunity status from DB
                            try {
                                var opp = missingOppMap.get(oppId);
                                if (opp != null) {
                                    String oppStatus = opp.getStatus() != null ? opp.getStatus() : "EXPIRED";

                                    // For EXITED/CLOSED: use exitTime from live_positions or opportunity
                                    if ("EXITED".equals(oppStatus) || "CLOSED".equals(oppStatus)) {
                                        statusMap.put(oppIdStr, oppStatus);
                                        // EXITED/CLOSED: actual P&L and exit time
                                        if (opp.getExitTime() != null) {
                                            exitTimeMap.put(oppIdStr, opp.getExitTime().toString());
                                        }
                                        pnlMap.put(oppIdStr, opp.getPnlAfterCosts() != null ? Math.round(opp.getPnlAfterCosts().doubleValue()) : 0);
                                    } else if ("EXPIRED".equals(oppStatus)) {
                                        statusMap.put(oppIdStr, oppStatus);
                                        // EXPIRED: contract expired, never entered — show edge as potential
                                        if (opp.getExpiryDate() != null) {
                                            exitTimeMap.put(oppIdStr, opp.getExpiryDate().toString());
                                        }
                                        pnlMap.put(oppIdStr, opp.getEdgeAfterCosts() != null ? Math.round(opp.getEdgeAfterCosts().doubleValue()) : 0);
                                    } else if ("RUNNING".equals(oppStatus) && "BID_PARITY".equals(opp.getStrategyType())) {
                                        // RUNNING bid-parity signal, never traded — simulate live mark-to-market
                                        // P&L against current quotes and auto-exit once edge target is hit.
                                        String ceSym = ceSymByOpp.get(oppId);
                                        String peSym = peSymByOpp.get(oppId);
                                        double ceCurrent = (ceSym != null && bpQuotes.containsKey(ceSym)) ? bpQuotes.get(ceSym).lastPrice : 0;
                                        double peCurrent = (peSym != null && bpQuotes.containsKey(peSym)) ? bpQuotes.get(peSym).lastPrice : 0;

                                        double[] futSpotFut = futLiveByUnderlying.computeIfAbsent(opp.getUnderlying(), u -> {
                                            try {
                                                String spotKey = spotKeyMap.getOrDefault(u, "NSE:NIFTY 50");
                                                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotFetcher, spotKey);
                                                return spotFetcher.getSpotAndFutures(spotKey, futKey);
                                            } catch (Exception e) {
                                                return new double[]{0, 0};
                                            }
                                        });
                                        double futCurrent = (futSpotFut != null && futSpotFut.length > 1) ? futSpotFut[1] : 0;

                                        double ceEntry = opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0;
                                        double peEntry = opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0;
                                        double futEntry = opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0;
                                        int lotSz = OptionChainService.getLotSize(opp.getUnderlying());
                                        String action = opp.getAction() != null ? opp.getAction().toUpperCase() : "";

                                        boolean havePrices = ceCurrent > 0 || peCurrent > 0 || futCurrent > 0;
                                        double pnl = 0;
                                        if (havePrices) {
                                            if (action.contains("BUY CE +")) {
                                                if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                                                if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                                                if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                                            } else {
                                                if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                                                if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                                                if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
                                            }
                                            pnl *= lotSz;
                                        }

                                        double edgeTarget = opp.getEdgeAfterCosts() != null ? opp.getEdgeAfterCosts().doubleValue() : 0;
                                        if (havePrices && edgeTarget > 0 && pnl >= edgeTarget) {
                                            opp.setStatus("EXITED");
                                            opp.setExitTime(LocalDateTime.now());
                                            opp.setCeExitPrice(BigDecimal.valueOf(ceCurrent));
                                            opp.setPeExitPrice(BigDecimal.valueOf(peCurrent));
                                            opp.setExitSpotPrice(BigDecimal.valueOf(futCurrent));
                                            opp.setPnlPoints(BigDecimal.valueOf(lotSz > 0 ? pnl / lotSz : 0));
                                            opp.setPnlAmount(BigDecimal.valueOf(pnl));
                                            opp.setPnlAfterCosts(BigDecimal.valueOf(pnl));
                                            try { oppRepo.save(opp); } catch (Exception ignored) {}
                                            statusMap.put(oppIdStr, "EXITED");
                                            exitTimeMap.put(oppIdStr, opp.getExitTime().toString());
                                            pnlMap.put(oppIdStr, Math.round(pnl));
                                        } else {
                                            statusMap.put(oppIdStr, oppStatus);
                                            pnlMap.put(oppIdStr, havePrices ? Math.round(pnl) : null);
                                        }
                                    } else {
                                        statusMap.put(oppIdStr, oppStatus);
                                        // MISSED / other: never entered, no P&L, no exit
                                        pnlMap.put(oppIdStr, null);
                                    }
                                } else {
                                    statusMap.put(oppIdStr, "EXPIRED");
                                    pnlMap.put(oppIdStr, 0);
                                }
                            } catch (Exception ex) {
                                statusMap.put(oppIdStr, "EXPIRED");
                                pnlMap.put(oppIdStr, 0);
                            }
                        }
                    }
                }
            }

            resp.put("pnlMap", pnlMap);
            resp.put("statusMap", statusMap);
            resp.put("exitTimeMap", exitTimeMap);
            resp.put("marketOpen", marketOpen);
        } catch (Exception e) {
            log.error("Failed to compute live P&L: {}", e.getMessage());
            resp.put("pnlMap", Map.of());
            resp.put("statusMap", Map.of());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/auto-execute/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(autoExecService.getSettings());
    }

    @PostMapping("/auto-execute/settings")
    public ResponseEntity<Map<String, Object>> updateSetting(@RequestParam String key, @RequestParam String value) {
        try {
            autoExecService.updateSetting(key, value);
            addAuditLog("SETTINGS", "INFO", "Updated setting '" + key + "' = " + value);
        } catch (Exception e) {
            log.error("Failed to update setting: {}", e.getMessage());
        }
        return ResponseEntity.ok(autoExecService.getSettings());
    }

    @PostMapping("/auto-execute/run")
    public ResponseEntity<Map<String, Object>> runAutoExecNow() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        addAuditLog("EXECUTION", "SUCCESS", "Manual auto-execute triggered");
        response.put("status", "TRIGGERED");
        response.put("message", "Auto-execute cycle triggered. Check logs for results.");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/auto-execute/logs")
    public ResponseEntity<List<Map<String, Object>>> getAuditLogs() {
        return ResponseEntity.ok(autoExecService.getExecLogs());
    }

    @GetMapping("/live-positions")
    public ResponseEntity<Map<String, Object>> getLivePositions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<LivePosition> openPositions = livePositionRepo.findAllOpen();

        Map<String, OptionChainService.OptionQuote> allQuotes;
        try {
            List<String> symbols = new ArrayList<>();
            for (LivePosition p : openPositions) {
                if (p.getCeSymbol() != null) symbols.add(p.getCeSymbol());
                if (p.getPeSymbol() != null) symbols.add(p.getPeSymbol());
                if (p.getFutSymbol() != null) symbols.add(p.getFutSymbol());
            }
            allQuotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            log.debug("Live positions quote fetch failed: {}", e.getMessage());
            allQuotes = Map.of();
        }

        final Map<String, OptionChainService.OptionQuote> quotes = allQuotes;

        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean marketOpen = !nowIST.isBefore(LocalTime.of(9, 15)) && !nowIST.isAfter(LocalTime.of(15, 30));

        List<Map<String, Object>> posList = openPositions.stream().map(p -> {
            Map<String, Object> map = p.toMap();

            double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
            if (p.getCeSymbol() != null && quotes.containsKey(p.getCeSymbol())) ceCurrent = quotes.get(p.getCeSymbol()).lastPrice;
            if (p.getPeSymbol() != null && quotes.containsKey(p.getPeSymbol())) peCurrent = quotes.get(p.getPeSymbol()).lastPrice;
            if (p.getFutSymbol() != null && quotes.containsKey(p.getFutSymbol())) futCurrent = quotes.get(p.getFutSymbol()).lastPrice;

            double ceEntry = p.getCeEntryPrice() != null ? p.getCeEntryPrice().doubleValue() : 0;
            double peEntry = p.getPeEntryPrice() != null ? p.getPeEntryPrice().doubleValue() : 0;
            double futEntry = p.getFutEntryPrice() != null ? p.getFutEntryPrice().doubleValue() : 0;
            int lotSize = p.getLotSize() != null ? p.getLotSize() : getLotSize(p.getUnderlying());
            int lots = p.getLots() != null ? p.getLots() : 1;

            double pnl = 0;
            String action = p.getAction() != null ? p.getAction().toUpperCase() : "";
            if (ceCurrent > 0 || peCurrent > 0 || futCurrent > 0) {
                if (action.contains("BUY CE +")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                } else if (action.contains("SELL CE +")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                    if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                    if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
                } else {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                }
            }
            pnl *= lotSize * lots;
            map.put("currentPnl", Math.round(pnl));
            map.put("ceCurrent", ceCurrent);
            map.put("peCurrent", peCurrent);
            map.put("futCurrent", futCurrent);

            double target = p.getTargetEdge() != null ? p.getTargetEdge().doubleValue() : 0;
            double pnlPerLot = lots > 0 ? Math.abs(pnl) / lots : 0;
            double edgeCaptured = target > 0 ? Math.min(100, Math.round(pnlPerLot / target * 100)) : 0;
            map.put("edgeCaptured", edgeCaptured);
            map.put("marketOpen", marketOpen);

            return map;
        }).toList();

        resp.put("positions", posList);
        resp.put("count", posList.size());
        resp.put("marketOpen", marketOpen);
        double totalPnl = posList.stream().mapToDouble(p -> {
            Object pnl = p.get("currentPnl");
            return pnl != null ? ((Number) pnl).doubleValue() : 0;
        }).sum();
        resp.put("totalPnl", Math.round(totalPnl));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/rollover/{positionId}")
    public ResponseEntity<Map<String, Object>> rolloverPosition(@PathVariable Long positionId) {
        Map<String, Object> result = autoExecService.rollPosition(positionId);
        if (result.containsKey("error")) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/paper-trades")
    public ResponseEntity<Map<String, Object>> getPaperTrades(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String underlying,
            @RequestParam(required = false) String mode) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<LivePosition> positions;
        if ("CLOSED".equalsIgnoreCase(status) || "EXITED".equalsIgnoreCase(status)) {
            positions = livePositionRepo.findAllClosed();
        } else if ("OPEN".equalsIgnoreCase(status)) {
            positions = livePositionRepo.findAllOpen();
        } else if ("FAILED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)) {
            positions = livePositionRepo.findAllFailed();
        } else {
            positions = livePositionRepo.findAllByOrderByEnteredAtDesc();
        }
        if (underlying != null && !underlying.isEmpty() && !"ALL".equalsIgnoreCase(underlying)) {
            positions = positions.stream().filter(p -> underlying.equalsIgnoreCase(p.getUnderlying())).toList();
        }
        if ("PAPER".equalsIgnoreCase(mode) || "LIVE".equalsIgnoreCase(mode)) {
            boolean wantPaper = "PAPER".equalsIgnoreCase(mode);
            positions = positions.stream().filter(p -> {
                boolean isPaper = p.getCeOrderId() != null && p.getCeOrderId().startsWith("PAPER");
                return wantPaper == isPaper;
            }).toList();
        }

        // Compute P&L for all positions
        List<String> symbols = new ArrayList<>();
        for (LivePosition p : positions) {
            if ("OPEN".equals(p.getStatus())) {
                if (p.getCeSymbol() != null) symbols.add(p.getCeSymbol());
                if (p.getPeSymbol() != null) symbols.add(p.getPeSymbol());
                if (p.getFutSymbol() != null) symbols.add(p.getFutSymbol());
            }
        }
        Map<String, OptionChainService.OptionQuote> quotes = Map.of();
        try {
            quotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
        } catch (Exception e) {
            log.debug("Paper trades quote fetch failed: {}", e.getMessage());
        }

        final Map<String, OptionChainService.OptionQuote> q = quotes;
        List<Map<String, Object>> posList = positions.stream().map(p -> {
            Map<String, Object> m = p.toMap();
            double pnl = 0;
            String action = p.getAction() != null ? p.getAction().toUpperCase() : "";
            int lotSize = p.getLotSize() != null ? p.getLotSize() : getLotSize(p.getUnderlying());
            int lots = p.getLots() != null ? p.getLots() : 1;

            if ("CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus())) {
                // Use stored P&L or compute from exit prices
                if (p.getCurrentPnl() != null) {
                    pnl = p.getCurrentPnl().doubleValue();
                } else if (p.getCeExitPrice() != null || p.getPeExitPrice() != null || p.getFutExitPrice() != null) {
                    double ceEntry = p.getCeEntryPrice() != null ? p.getCeEntryPrice().doubleValue() : 0;
                    double peEntry = p.getPeEntryPrice() != null ? p.getPeEntryPrice().doubleValue() : 0;
                    double futEntry = p.getFutEntryPrice() != null ? p.getFutEntryPrice().doubleValue() : 0;
                    double ceExit = p.getCeExitPrice() != null ? p.getCeExitPrice().doubleValue() : 0;
                    double peExit = p.getPeExitPrice() != null ? p.getPeExitPrice().doubleValue() : 0;
                    double futExit = p.getFutExitPrice() != null ? p.getFutExitPrice().doubleValue() : 0;
                    if (action.contains("BUY CE +")) {
                        if (ceExit > 0 && ceEntry > 0) pnl += ceExit - ceEntry;
                        if (peExit > 0 && peEntry > 0) pnl += peEntry - peExit;
                        if (futExit > 0 && futEntry > 0) pnl += futEntry - futExit;
                    } else {
                        if (ceExit > 0 && ceEntry > 0) pnl += ceEntry - ceExit;
                        if (peExit > 0 && peEntry > 0) pnl += peExit - peEntry;
                        if (futExit > 0 && futEntry > 0) pnl += futExit - futEntry;
                    }
                    pnl *= lotSize * lots;
                }
            } else {
                // OPEN — compute from live quotes
                double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
                if (p.getCeSymbol() != null && q.containsKey(p.getCeSymbol())) ceCurrent = q.get(p.getCeSymbol()).lastPrice;
                if (p.getPeSymbol() != null && q.containsKey(p.getPeSymbol())) peCurrent = q.get(p.getPeSymbol()).lastPrice;
                if (p.getFutSymbol() != null && q.containsKey(p.getFutSymbol())) futCurrent = q.get(p.getFutSymbol()).lastPrice;
                double ceEntry = p.getCeEntryPrice() != null ? p.getCeEntryPrice().doubleValue() : 0;
                double peEntry = p.getPeEntryPrice() != null ? p.getPeEntryPrice().doubleValue() : 0;
                double futEntry = p.getFutEntryPrice() != null ? p.getFutEntryPrice().doubleValue() : 0;
                if (action.contains("BUY CE +")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                } else {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                    if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                    if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
                }
                pnl *= lotSize * lots;
                m.put("ceCurrent", ceCurrent);
                m.put("peCurrent", peCurrent);
                m.put("futCurrent", futCurrent);
            }
            m.put("pnl", Math.round(pnl));
            return m;
        }).toList();

        long openCount = positions.stream().filter(p -> "OPEN".equals(p.getStatus())).count();
        long closedCount = positions.stream().filter(p -> "CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus())).count();
        long failedCount = positions.stream().filter(p -> "FAILED".equals(p.getStatus()) || "REJECTED".equals(p.getStatus())).count();
        long paperCount = positions.stream().filter(p -> p.getCeOrderId() != null && p.getCeOrderId().startsWith("PAPER")).count();
        long liveCount = positions.size() - paperCount;
        double totalPnl = posList.stream().mapToDouble(m -> {
            Object pnl = m.get("pnl");
            return pnl != null ? ((Number) pnl).doubleValue() : 0;
        }).sum();

        resp.put("positions", posList);
        resp.put("total", posList.size());
        resp.put("openCount", openCount);
        resp.put("closedCount", closedCount);
        resp.put("failedCount", failedCount);
        resp.put("paperCount", paperCount);
        resp.put("liveCount", liveCount);
        resp.put("totalPnl", Math.round(totalPnl));
        return ResponseEntity.ok(resp);
    }

    private int getLotSize(String underlying) {
        return switch (underlying) {
            case "NIFTY" -> 25;
            case "BANKNIFTY" -> 15;
            case "MIDCPNIFTY" -> 50;
            case "FINNIFTY" -> 25;
            default -> 25;
        };
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

    @GetMapping("/auto-execute/execute")
    public ResponseEntity<Map<String, Object>> executeOpportunity(
            @RequestParam Long opportunityId,
            @RequestParam(defaultValue = "1") int multiplier) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("opportunityId", opportunityId);
        resp.put("multiplier", multiplier);
        resp.put("status", "SUBMITTED");
        resp.put("message", "Order submitted for execution");
        return ResponseEntity.ok(resp);
    }

    @PostMapping(value = "/paper-trade/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> executePaperTrade(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            Number opportunityId = (Number) body.get("opportunityId");
            OptionArbOpportunity opp = null;

            if (opportunityId != null) {
                Optional<OptionArbOpportunity> opt = historyService.getRepository().findById(opportunityId.longValue());
                if (opt.isPresent()) {
                    opp = opt.get();
                    String currentStatus = opp.getStatus();
                    if ("CLOSED".equals(currentStatus) || "EXPIRED".equals(currentStatus) || "EXITED".equals(currentStatus)) {
                        resp.put("status", "ERROR");
                        resp.put("message", "Trade already " + currentStatus);
                        return ResponseEntity.ok(resp);
                    }
                }
            }

            // If no DB opportunity found, create one from the scan data in the request
            if (opp == null) {
                String underlying = (String) body.getOrDefault("underlying", "NIFTY");
                Number strikeNum = (Number) body.get("strike");
                String action = (String) body.getOrDefault("action", "BUY FUT + SELL CE + BUY PE");
                String strategyType = (String) body.getOrDefault("strategyType", "BID_PARITY");
                String description = (String) body.getOrDefault("description", strategyType + " " + underlying + " " + (strikeNum != null ? strikeNum.intValue() : ""));
                Number edgeNum = (Number) body.getOrDefault("edgeAfterCosts", 0);
                Number ceEntry = (Number) body.getOrDefault("ceEntryPrice", 0);
                Number peEntry = (Number) body.getOrDefault("peEntryPrice", 0);
                Number spotPrice = (Number) body.getOrDefault("spotPrice", 0);
                Number futPrice = (Number) body.getOrDefault("futuresPrice", 0);

                LocalDate expiry = optionChainService.getWeeklyExpiryDate(underlying);
                int lotSize = getLotSize(underlying);

                opp = OptionArbOpportunity.builder()
                    .scanTime(LocalDateTime.now())
                    .underlying(underlying)
                    .type(strategyType)
                    .strike(strikeNum != null ? strikeNum.intValue() : 0)
                    .action(action)
                    .legs(action + " " + underlying + " " + (strikeNum != null ? strikeNum.intValue() : ""))
                    .strategyType(strategyType)
                    .description(description)
                    .spotPrice(spotPrice != null ? BigDecimal.valueOf(spotPrice.doubleValue()) : BigDecimal.ZERO)
                    .futuresPrice(futPrice != null ? BigDecimal.valueOf(futPrice.doubleValue()) : BigDecimal.ZERO)
                    .edgePoints(BigDecimal.ZERO)
                    .edgeAfterCosts(BigDecimal.valueOf(edgeNum.doubleValue()))
                    .ceEntryPrice(ceEntry != null ? BigDecimal.valueOf(ceEntry.doubleValue()) : BigDecimal.ZERO)
                    .peEntryPrice(peEntry != null ? BigDecimal.valueOf(peEntry.doubleValue()) : BigDecimal.ZERO)
                    .expiryDate(expiry)
                    .status("RUNNING")
                    .build();
                opp = historyService.getRepository().save(opp);
                log.info("Created opportunity from scan data: id={}", opp.getId());
            }

            // Fetch live CE/PE prices as entry prices
            double ceLive = 0, peLive = 0;
            try {
                if (opp.getExpiryDate() != null && opp.getStrike() != null) {
                    String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                    String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                    Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceSymbol, peSymbol));
                    if (quotes.containsKey(ceSymbol) && quotes.get(ceSymbol).lastPrice > 0) ceLive = quotes.get(ceSymbol).lastPrice;
                    if (quotes.containsKey(peSymbol) && quotes.get(peSymbol).lastPrice > 0) peLive = quotes.get(peSymbol).lastPrice;
                }
            } catch (Exception e) {
                log.warn("Could not fetch live prices for entry: {}", e.getMessage());
            }

            // Update entry prices with live quotes
            if (ceLive > 0) opp.setCeEntryPrice(BigDecimal.valueOf(ceLive));
            if (peLive > 0) opp.setPeEntryPrice(BigDecimal.valueOf(peLive));

            // Update status to RUNNING
            opp.setStatus("RUNNING");

            historyService.getRepository().save(opp);

            // Create a LivePosition so it shows in Live Positions section
            try {
                long openCount = livePositionRepo.countAllOpen();
                if (openCount >= 1) {
                    resp.put("status", "ERROR");
                    resp.put("message", "Already have 1 open position. Close it first.");
                    return ResponseEntity.badRequest().body(resp);
                }
                int lotSize = getLotSize(opp.getUnderlying());
                String futSymbol = opp.getExpiryDate() != null
                    ? optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate()) : null;
                double futLive = 0;
                if (futSymbol != null) {
                    try {
                        var futQuotes = optionChainService.fetchQuotes(List.of(futSymbol));
                        if (futQuotes.containsKey(futSymbol) && futQuotes.get(futSymbol).lastPrice > 0) {
                            futLive = futQuotes.get(futSymbol).lastPrice;
                        }
                    } catch (Exception ignored) {}
                }

                LivePosition livePos = LivePosition.builder()
                    .userId(1L)
                    .opportunityId(opp.getId())
                    .underlying(opp.getUnderlying())
                    .strike(opp.getStrike())
                    .action(opp.getAction())
                    .strategyType(opp.getStrategyType())
                    .lots(1)
                    .lotSize(lotSize)
                    .ceEntryPrice(ceLive > 0 ? BigDecimal.valueOf(ceLive) : null)
                    .peEntryPrice(peLive > 0 ? BigDecimal.valueOf(peLive) : null)
                    .futEntryPrice(futLive > 0 ? BigDecimal.valueOf(futLive) : null)
                    .futSymbol(futSymbol)
                    .ceSymbol(optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE"))
                    .peSymbol(optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE"))
                    .targetEdge(BigDecimal.valueOf(recalculateTargetEdge(
                        ceLive, peLive, futLive, opp.getStrike(), opp.getAction(), opp.getUnderlying(),
                        opp.getExpiryDate() != null ? java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), opp.getExpiryDate()) : 0)))
                    .entryCost(BigDecimal.valueOf((ceLive + peLive + futLive) * lotSize))
                    .status("OPEN")
                    .enteredAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .build();
                livePositionRepo.save(livePos);
                resp.put("livePositionId", livePos.getId());
            } catch (Exception e) {
                log.warn("Failed to create live position: {}", e.getMessage());
            }

            addAuditLog("PAPER_TRADE", "SUCCESS",
                "Entered " + opp.getUnderlying() + " " + opp.getStrike() + " " + opp.getAction()
                + " | CE=" + String.format("%.1f", ceLive) + " PE=" + String.format("%.1f", peLive));

            resp.put("status", "SUCCESS");
            resp.put("opportunityId", opp.getId());
            resp.put("underlying", opp.getUnderlying());
            resp.put("strike", opp.getStrike());
            resp.put("action", opp.getAction());
            resp.put("ceEntryPrice", ceLive);
            resp.put("peEntryPrice", peLive);
            resp.put("tradeStatus", "RUNNING");
            resp.put("message", opp.getUnderlying() + " " + opp.getStrike() + " entered as PAPER trade");

        } catch (Exception e) {
            log.error("Paper trade execution failed: {}", e.getMessage(), e);
            resp.put("status", "ERROR");
            resp.put("message", "Execution failed: " + e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/backtest")
    public ResponseEntity<Map<String, Object>> backtest(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1000") double minEdge,
            @RequestParam(defaultValue = "1") int lots,
            @RequestParam(defaultValue = "250000") double capital,
            @RequestParam(defaultValue = "3") int maxConcurrent) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            ZoneId ist = ZoneId.of("Asia/Kolkata");
            LocalDate today = LocalDate.now(ist);
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : today.minusDays(30);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : today;

            List<OptionArbOpportunity> allOpps;
            if ("ALL".equals(underlying)) {
                allOpps = historyService.getRepository().findByScanTimeBetween(start.atStartOfDay(), end.atTime(LocalTime.MAX));
            } else {
                allOpps = historyService.getRepository().findByScanTimeBetweenAndUnderlyingOrderByScanTimeDesc(start.atStartOfDay(), end.atTime(LocalTime.MAX), underlying);
            }

            List<OptionArbOpportunity> tradeable = allOpps.stream()
                .filter(o -> o.getEdgeAfterCosts() != null && o.getEdgeAfterCosts().doubleValue() >= minEdge)
                .filter(o -> o.getExpiryDate() != null && o.getStrike() != null)
                .sorted(Comparator.comparing(OptionArbOpportunity::getScanTime))
                .toList();

            // Deduplicate: pick best edge per (underlying, strike) per 5-minute window
            Map<String, OptionArbOpportunity> bestPerWindow = new LinkedHashMap<>();
            for (OptionArbOpportunity opp : tradeable) {
                if (opp.getScanTime() == null) continue;
                long minuteBucket = opp.getScanTime().atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond() / 300;
                String dedupeKey = opp.getUnderlying() + "_" + opp.getStrike() + "_" + minuteBucket;
                OptionArbOpportunity existing = bestPerWindow.get(dedupeKey);
                if (existing == null || opp.getEdgeAfterCosts().doubleValue() > existing.getEdgeAfterCosts().doubleValue()) {
                    bestPerWindow.put(dedupeKey, opp);
                }
            }
            List<OptionArbOpportunity> deduped = new ArrayList<>(bestPerWindow.values());
            deduped.sort(Comparator.comparing(OptionArbOpportunity::getScanTime));

            // Simulate: pick best edge per underlying per minute window
            List<Map<String, Object>> trades = new ArrayList<>();
            double totalPnl = 0;
            double maxDrawdown = 0;
            double peakPnl = 0;
            int wins = 0, losses = 0;
            Map<String, Integer> underlyingTrades = new LinkedHashMap<>();
            double[] dailyPnl = new double[31];

            long currentDayStart = 0;
            int dayIndex = 0;

            for (OptionArbOpportunity opp : deduped) {
                int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
                double edge = opp.getEdgeAfterCosts().doubleValue();
                double tradePnl = edge * lots;
                totalPnl += tradePnl;
                if (tradePnl > 0) wins++; else losses++;

                if (totalPnl > peakPnl) peakPnl = totalPnl;
                double dd = peakPnl - totalPnl;
                if (dd > maxDrawdown) maxDrawdown = dd;

                if (opp.getScanTime() != null) {
                    long dayMillis = opp.getScanTime().toLocalDate().atStartOfDay(ist).toInstant().toEpochMilli();
                    if (dayMillis != currentDayStart) {
                        currentDayStart = dayMillis;
                        dayIndex++;
                    }
                    if (dayIndex < dailyPnl.length) dailyPnl[dayIndex] += tradePnl;
                }

                underlyingTrades.merge(opp.getUnderlying(), 1, Integer::sum);

                Map<String, Object> trade = new LinkedHashMap<>();
                trade.put("time", opp.getScanTime() != null ? opp.getScanTime().toString() : "");
                trade.put("underlying", opp.getUnderlying());
                trade.put("strike", opp.getStrike());
                trade.put("action", opp.getAction());
                trade.put("edge", Math.round(edge));
                trade.put("pnl", Math.round(tradePnl));
                trade.put("lotSize", lotSize);
                trades.add(trade);
            }

            double avgDailyPnl = dayIndex > 0 ? totalPnl / dayIndex : 0;
            double winRate = (wins + losses) > 0 ? (double) wins / (wins + losses) * 100 : 0;

            resp.put("period", start + " to " + end);
            resp.put("totalSignals", allOpps.size());
            resp.put("tradeableSignals", deduped.size());
            resp.put("minEdgeFilter", minEdge);
            resp.put("capital", capital);
            resp.put("lots", lots);
            resp.put("totalPnl", Math.round(totalPnl));
            resp.put("avgDailyPnl", Math.round(avgDailyPnl));
            resp.put("maxDrawdown", Math.round(maxDrawdown));
            resp.put("wins", wins);
            resp.put("losses", losses);
            resp.put("winRate", Math.round(winRate * 10.0) / 10.0);
            resp.put("underlyingBreakdown", underlyingTrades);
            resp.put("trades", trades.stream().limit(200).toList());
        } catch (Exception e) {
            log.error("Backtest failed: {}", e.getMessage(), e);
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/margin-check")
    public ResponseEntity<Map<String, Object>> marginCheck(
            @RequestParam String underlying,
            @RequestParam(defaultValue = "1") int lots) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            int lotSize = OptionChainService.getLotSize(underlying);

            // Hedged margin estimates for box spread (FUT + CE + PE)
            Map<String, Double> hedgedMargins = Map.of(
                "NIFTY", 150000.0,
                "BANKNIFTY", 250000.0,
                "MIDCPNIFTY", 180000.0,
                "FINNIFTY", 200000.0
            );
            double estimatedMargin = hedgedMargins.getOrDefault(underlying, 200000.0) * lots * 1.15;

            resp.put("underlying", underlying);
            resp.put("lots", lots);
            resp.put("lotSize", lotSize);
            resp.put("estimatedMargin", Math.round(estimatedMargin));
            resp.put("capitalRequired", Math.round(estimatedMargin));
            resp.put("note", "Estimated margin for hedged box spread. Actual margin from broker may differ.");
        } catch (Exception e) {
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/export-signals")
    public ResponseEntity<byte[]> exportSignalsCsv(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        StringBuilder csv = new StringBuilder();
        csv.append("ID,ScanTime,Underlying,Strike,Action,CE_Price,PE_Price,Edge_Points,Net_Edge_Profit,DTE,Expiry\n");

        try {
            ZoneId ist = ZoneId.of("Asia/Kolkata");
            LocalDate today = LocalDate.now(ist);
            LocalDate start = startDate != null ? LocalDate.parse(startDate) : today.minusDays(6);
            LocalDate end = endDate != null ? LocalDate.parse(endDate) : today;

            List<OptionArbOpportunity> opps;
            if ("ALL".equals(underlying)) {
                opps = historyService.getRepository()
                    .findByScanTimeBetween(start.atStartOfDay(), end.atTime(LocalTime.MAX));
            } else {
                opps = historyService.getRepository()
                    .findByScanTimeBetweenAndUnderlyingOrderByScanTimeDesc(start.atStartOfDay(), end.atTime(LocalTime.MAX), underlying);
            }

            for (OptionArbOpportunity opp : opps) {
                csv.append(opp.getId()).append(",")
                   .append(opp.getScanTime()).append(",")
                   .append(opp.getUnderlying()).append(",")
                   .append(opp.getStrike()).append(",")
                   .append(opp.getAction()).append(",")
                   .append(opp.getCeEntryPrice()).append(",")
                   .append(opp.getPeEntryPrice()).append(",")
                   .append(opp.getEdgePoints()).append(",")
                   .append(opp.getEdgeAfterCosts()).append(",")
                   .append(opp.getDaysToExpiry()).append(",")
                   .append(opp.getExpiryDate()).append("\n");
            }
        } catch (Exception e) {
            log.error("Export failed: {}", e.getMessage());
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=signals_export.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(bytes);
    }

    private void triggerAutoExec() {
        try {
            List<OptionArbOpportunity> recentOpps = historyService.getRepository()
                .findRecentByStatusLimited("DETECTED", LocalDateTime.now().minusSeconds(60), 50);
            if (recentOpps.isEmpty()) {
                recentOpps = historyService.getRepository()
                    .findRecentByStatusLimited("RUNNING", LocalDateTime.now().minusSeconds(60), 50);
            }
            if (!recentOpps.isEmpty()) {
                autoExecService.evaluateAndExecute(recentOpps);
            }
        } catch (Exception e) {
            log.debug("Auto-exec trigger failed: {}", e.getMessage());
        }
    }
}
