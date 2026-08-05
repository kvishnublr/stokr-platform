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

    private final Map<String, Object> autoExecSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> auditLogs = Collections.synchronizedList(new ArrayList<>());

    public OptionArbitrageController(OptionChainService optionChainService,
                                     OptionArbHistoryService historyService,
                                     BidParityService bidParityService,
                                     BoxSpreadService boxSpreadService,
                                     CalendarSpreadService calendarSpreadService,
                                     ZerodhaSpotPriceFetcher spotFetcher,
                                     OptionArbAutoExecService autoExecService,
                                     LivePositionRepository livePositionRepo) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.bidParityService = bidParityService;
        this.boxSpreadService = boxSpreadService;
        this.calendarSpreadService = calendarSpreadService;
        this.spotFetcher = spotFetcher;
        this.autoExecService = autoExecService;
        this.livePositionRepo = livePositionRepo;

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

        if (!opps.isEmpty()) triggerAutoExec();

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
        if (opps != null && !opps.isEmpty()) triggerAutoExec();
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
        if (opps != null && !opps.isEmpty()) triggerAutoExec();
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
        List<String> targets = "ALL".equalsIgnoreCase(underlying)
            ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
            : List.of(underlying);
        Map<String, String> spotKeys = Map.of(
            "NIFTY", "NSE:NIFTY 50",
            "BANKNIFTY", "NSE:NIFTY BANK",
            "MIDCPNIFTY", "NSE:NIFTY MID SELECT",
            "FINNIFTY", "NSE:NIFTY FIN SERVICE"
        );
        List<Map<String, Object>> allOpps = new ArrayList<>();
        for (String u : targets) {
            try {
                String spotKey = spotKeys.getOrDefault(u, "NSE:NIFTY 50");
                String futKey = FuturesKeyResolver.resolveFuturesKey(u, spotFetcher, spotKey);
                double[] spotFut = spotFetcher.getSpotAndFutures(spotKey, futKey);
                double spot = (spotFut != null && spotFut.length > 0 && spotFut[0] > 0) ? spotFut[0] : 0;
                double fut = (spotFut != null && spotFut.length > 1 && spotFut[1] > 0) ? spotFut[1] : spot;
                if (spot <= 0) continue;
                List<Map<String, Object>> opps = calendarSpreadService.scanCalendarSpreads(u, spot, fut);
                allOpps.addAll(opps);
            } catch (Exception e) {
                log.error("Calendar scan error for {}: {}", u, e.getMessage());
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", allOpps);
        resp.put("count", allOpps.size());
        return ResponseEntity.ok(resp);
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
        if (!allOpps.isEmpty()) triggerAutoExec();
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
            LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            boolean marketOpen = !nowIST.isBefore(LocalTime.of(9, 15)) && !nowIST.isAfter(LocalTime.of(15, 30));

            List<OptionArbOpportunity> allOpen = new ArrayList<>();
            if (ids != null && !ids.isEmpty()) {
                List<Long> idList = Arrays.stream(ids.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::parseLong).collect(Collectors.toList());
                if (!idList.isEmpty()) {
                    allOpen.addAll(historyService.getRepository().findAllById(idList));
                }
            } else {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
                LocalDateTime since = today.minusDays(1).atStartOfDay();
                allOpen.addAll(historyService.getRepository().findRecentByStatusLimited("RUNNING", since, 500));
                allOpen.addAll(historyService.getRepository().findRecentByStatusLimited("OPEN", since, 100));
                allOpen.addAll(historyService.getRepository().findRecentByStatusLimited("DETECTED", since, 100));
                allOpen = allOpen.stream().distinct().limit(500).collect(Collectors.toList());
            }

            Map<String, OptionChainService.OptionQuote> allQuotes = Map.of();
            try {
                List<String> symbols = new ArrayList<>();
                for (OptionArbOpportunity opp : allOpen) {
                    if (opp.getExpiryDate() != null && opp.getStrike() != null) {
                        symbols.add(optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE"));
                        symbols.add(optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE"));
                        symbols.add(optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate()));
                    }
                }
                allQuotes = optionChainService.fetchQuotes(symbols);
            } catch (Exception e) {
                log.debug("Batch quote fetch failed: {}", e.getMessage());
            }

            for (OptionArbOpportunity opp : allOpen) {
                int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
                double ceP = opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0;
                double peP = opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0;
                double futP = opp.getFuturesPrice() != null ? opp.getFuturesPrice().doubleValue() : 0;

                double currentCe = 0;
                double currentPe = 0;
                double currentFut = 0;
                if (opp.getExpiryDate() != null && opp.getStrike() != null) {
                    try {
                        String ceSym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                        String peSym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                        String futSym = optionChainService.buildNfoFutSymbol(opp.getUnderlying(), opp.getExpiryDate());
                        if (allQuotes.containsKey(ceSym) && allQuotes.get(ceSym).lastPrice > 0) currentCe = allQuotes.get(ceSym).lastPrice;
                        if (allQuotes.containsKey(peSym) && allQuotes.get(peSym).lastPrice > 0) currentPe = allQuotes.get(peSym).lastPrice;
                        if (allQuotes.containsKey(futSym) && allQuotes.get(futSym).lastPrice > 0) currentFut = allQuotes.get(futSym).lastPrice;
                    } catch (Exception e) {
                        log.debug("Quote lookup failed for {} {}: {}", opp.getUnderlying(), opp.getStrike(), e.getMessage());
                    }
                }

                double pnl = 0;
                String act = opp.getAction() != null ? opp.getAction().toUpperCase() : "";
                if (currentCe > 0 || currentPe > 0 || currentFut > 0) {
                    if ("CONVERSION".equalsIgnoreCase(opp.getAction()) || act.contains("BUY CE+PE")) {
                        if (currentCe > 0 && ceP > 0) pnl += currentCe - ceP;
                        if (currentPe > 0 && peP > 0) pnl += peP - currentPe;
                        if (currentFut > 0 && futP > 0) pnl += futP - currentFut;
                    } else if ("REVERSAL".equalsIgnoreCase(opp.getAction()) || act.contains("SELL CE+PE")) {
                        if (currentCe > 0 && ceP > 0) pnl += ceP - currentCe;
                        if (currentPe > 0 && peP > 0) pnl += currentPe - peP;
                        if (currentFut > 0 && futP > 0) pnl += currentFut - futP;
                    }
                    pnl *= lotSize;
                }
                pnlMap.put(String.valueOf(opp.getId()), Math.round(pnl * 100.0) / 100.0);
            }
            resp.put("pnlMap", pnlMap);
            resp.put("marketOpen", marketOpen);
        } catch (Exception e) {
            log.error("Failed to compute live P&L: {}", e.getMessage());
            resp.put("pnlMap", Map.of());
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
                if (action.contains("BUY CE+PE")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peEntry - peCurrent;
                    if (futCurrent > 0 && futEntry > 0) pnl += futEntry - futCurrent;
                } else if (action.contains("SELL CE+PE")) {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceEntry - ceCurrent;
                    if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
                    if (futCurrent > 0 && futEntry > 0) pnl += futCurrent - futEntry;
                } else {
                    if (ceCurrent > 0 && ceEntry > 0) pnl += ceCurrent - ceEntry;
                    if (peCurrent > 0 && peEntry > 0) pnl += peCurrent - peEntry;
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

    private int getLotSize(String underlying) {
        return switch (underlying) {
            case "NIFTY" -> 25;
            case "BANKNIFTY" -> 15;
            case "MIDCPNIFTY" -> 50;
            case "FINNIFTY" -> 25;
            default -> 25;
        };
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
            if (opportunityId == null) {
                resp.put("status", "ERROR");
                resp.put("message", "Missing opportunityId");
                return ResponseEntity.badRequest().body(resp);
            }

            Optional<OptionArbOpportunity> opt = historyService.getRepository().findById(opportunityId.longValue());
            if (opt.isEmpty()) {
                resp.put("status", "ERROR");
                resp.put("message", "Opportunity not found: " + opportunityId);
                return ResponseEntity.badRequest().body(resp);
            }

            OptionArbOpportunity opp = opt.get();
            String currentStatus = opp.getStatus();
            if ("CLOSED".equals(currentStatus) || "EXPIRED".equals(currentStatus)) {
                resp.put("status", "ERROR");
                resp.put("message", "Trade already " + currentStatus);
                return ResponseEntity.badRequest().body(resp);
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
                    .targetEdge(opp.getEdgeAfterCosts())
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
