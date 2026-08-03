package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbAutoExecService autoExecService;
    private final LivePositionRepository livePositionRepo;
    private final BidParityPaperSimulator paperSimulator;

    private final List<Map<String, Object>> auditLogs = Collections.synchronizedList(new ArrayList<>());

    public OptionArbitrageController(OptionChainService optionChainService,
                                     OptionArbHistoryService historyService,
                                     BidParityService bidParityService,
                                     BoxSpreadService boxSpreadService,
                                     ZerodhaSpotPriceFetcher spotFetcher,
                                     OptionArbAutoExecService autoExecService,
                                     LivePositionRepository livePositionRepo,
                                     BidParityPaperSimulator paperSimulator) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.bidParityService = bidParityService;
        this.boxSpreadService = boxSpreadService;
        this.spotFetcher = spotFetcher;
        this.autoExecService = autoExecService;
        this.livePositionRepo = livePositionRepo;
        this.paperSimulator = paperSimulator;
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
    public ResponseEntity<Map<String, Object>> scanBidParity(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(defaultValue = "MONTHLY") String expiry) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "expiryMode", expiry,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> opps = bidParityService.scanBidParity(underlying, expiry);
        if (opps != null && !opps.isEmpty()) triggerAutoExec();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("expiryMode", expiry);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        resp.put("note", "WEEKLY uses weekly options vs interpolated forward; hedge is still monthly FUT (basis risk).");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/bid-parity/history")
    public ResponseEntity<Map<String, Object>> bidParityHistory(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(defaultValue = "0") double minEdge,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "7") int days) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            ZoneId ist = ZoneId.of("Asia/Kolkata");
            LocalDate today = LocalDate.now(ist);
            LocalDate start = startDate != null && !startDate.isEmpty() ? LocalDate.parse(startDate) : today.minusDays(Math.max(0, days - 1));
            LocalDate end = endDate != null && !endDate.isEmpty() ? LocalDate.parse(endDate) : today;

            List<OptionArbOpportunity> all = historyService.getRepository()
                    .findByScanTimeBetween(start.atStartOfDay(), end.atTime(LocalTime.MAX));
            List<Map<String, Object>> filtered = all.stream()
                    .filter(o -> o.getStrategyType() != null && o.getStrategyType().toUpperCase().contains("BID"))
                    .filter(o -> "ALL".equalsIgnoreCase(underlying) || underlying.equalsIgnoreCase(o.getUnderlying()))
                    .filter(o -> o.getEdgeAfterCosts() != null && o.getEdgeAfterCosts().doubleValue() >= minEdge)
                    .sorted((a, b) -> {
                        if (a.getScanTime() == null || b.getScanTime() == null) return 0;
                        return b.getScanTime().compareTo(a.getScanTime());
                    })
                    .limit(1000)
                    .map(OptionArbOpportunity::toMap)
                    .toList();
            resp.put("items", filtered);
            resp.put("count", filtered.size());
            resp.put("startDate", start.toString());
            resp.put("endDate", end.toString());
        } catch (Exception e) {
            log.error("Bid parity history failed: {}", e.getMessage());
            resp.put("items", Collections.emptyList());
            resp.put("count", 0);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/bid-parity/paper-sim")
    public ResponseEntity<Map<String, Object>> bidParityPaperSim(
            @RequestParam(defaultValue = "NIFTY") String underlying,
            @RequestParam(defaultValue = "150") double minEdge,
            @RequestParam(defaultValue = "180000") double capital,
            @RequestParam(defaultValue = "2") int maxTradesPerDay,
            @RequestParam(defaultValue = "10") int days,
            @RequestParam(defaultValue = "0.6") double fillRate) {
        try {
            return ResponseEntity.ok(paperSimulator.run(
                    underlying, minEdge, capital, maxTradesPerDay, days, fillRate));
        } catch (Exception e) {
            log.error("Paper sim failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "sim failed",
                    "projection", Map.of(),
                    "daily", List.of(),
                    "topSignals", List.of()
            ));
        }
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
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
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
                                                       @RequestParam(defaultValue = "50") int size) {
        Map<String, Object> resp = new LinkedHashMap<>();
        var result = historyService.getHistory(page, size);
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
    public ResponseEntity<Map<String, Object>> livePnl() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            Map<String, Object> pnlMap = new LinkedHashMap<>();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<OptionArbOpportunity> openOpps = historyService.getRepository()
                .findByStatusOrderByScanTimeBetween("OPEN", today.atStartOfDay(), today.atTime(LocalTime.MAX));
            List<OptionArbOpportunity> detectedOpps = historyService.getRepository()
                .findByStatusOrderByScanTimeBetween("DETECTED", today.atStartOfDay(), today.atTime(LocalTime.MAX));
            List<OptionArbOpportunity> runningOpps = historyService.getRepository()
                .findByStatusOrderByScanTimeBetween("RUNNING", today.atStartOfDay(), today.atTime(LocalTime.MAX));

            List<OptionArbOpportunity> allOpen = new ArrayList<>();
            allOpen.addAll(openOpps);
            allOpen.addAll(detectedOpps);
            allOpen.addAll(runningOpps);

            for (OptionArbOpportunity opp : allOpen) {
                int lotSize = OptionChainService.getLotSize(opp.getUnderlying());
                double ceP = opp.getCeEntryPrice() != null ? opp.getCeEntryPrice().doubleValue() : 0;
                double peP = opp.getPeEntryPrice() != null ? opp.getPeEntryPrice().doubleValue() : 0;

                double currentCe = 0;
                double currentPe = 0;
                try {
                    if (optionChainService != null && opp.getExpiryDate() != null && opp.getStrike() != null) {
                        String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                        String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                        Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceSymbol, peSymbol));
                        if (quotes.containsKey(ceSymbol) && quotes.get(ceSymbol).lastPrice > 0) currentCe = quotes.get(ceSymbol).lastPrice;
                        if (quotes.containsKey(peSymbol) && quotes.get(peSymbol).lastPrice > 0) currentPe = quotes.get(peSymbol).lastPrice;
                    }
                } catch (Exception e) {
                    log.debug("Could not fetch live option prices for {} {}: {}", opp.getUnderlying(), opp.getStrike(), e.getMessage());
                }

                double pnl = 0;
                if ("CONVERSION".equalsIgnoreCase(opp.getAction())
                        || (opp.getAction() != null && opp.getAction().toUpperCase().contains("BUY CE")
                        && opp.getAction().toUpperCase().contains("SELL PE"))) {
                    pnl = ((currentCe - ceP) + (peP - currentPe)) * lotSize;
                } else if ("REVERSAL".equalsIgnoreCase(opp.getAction())
                        || (opp.getAction() != null && opp.getAction().toUpperCase().contains("SELL CE")
                        && opp.getAction().toUpperCase().contains("BUY PE"))) {
                    pnl = ((ceP - currentCe) + (currentPe - peP)) * lotSize;
                }
                pnlMap.put(String.valueOf(opp.getId()), Math.round(pnl * 100.0) / 100.0);
            }
            resp.put("pnlMap", pnlMap);
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
    public ResponseEntity<Map<String, Object>> updateSetting(
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String value,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty() && (key == null || key.isBlank())) {
            Map<String, Object> updated = autoExecService.updateSettingsBulk(body);
            addAuditLog("SETTINGS", "INFO", "Bulk updated Bid Parity settings (" + body.size() + " keys)");
            return ResponseEntity.ok(updated);
        }
        if (key == null || value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "key and value required (or JSON body for bulk)"));
        }
        autoExecService.updateSetting(key, value);
        addAuditLog("SETTINGS", "INFO", "Updated setting '" + key + "' = " + value);
        return ResponseEntity.ok(autoExecService.getSettings());
    }

    @PostMapping("/auto-execute/settings/bulk")
    public ResponseEntity<Map<String, Object>> updateSettingsBulk(@RequestBody Map<String, Object> body) {
        Map<String, Object> updated = autoExecService.updateSettingsBulk(body != null ? body : Map.of());
        addAuditLog("SETTINGS", "INFO", "Bulk updated Bid Parity settings");
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/auto-execute/readiness")
    public ResponseEntity<Map<String, Object>> autoExecReadiness() {
        return ResponseEntity.ok(autoExecService.probeBrokerReadiness());
    }

    @PostMapping("/auto-execute/run")
    public ResponseEntity<Map<String, Object>> runAutoExecNow() {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("timeIST", nowIST.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        response.put("marketOpen", !nowIST.isBefore(LocalTime.of(9, 15)) && !nowIST.isAfter(LocalTime.of(15, 30)));

        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            response.put("status", "SKIPPED");
            response.put("reason", "Off-market hours");
            return ResponseEntity.ok(response);
        }

        try {
            triggerAutoExec();
            addAuditLog("EXECUTION", "SUCCESS", "Manual auto-execute triggered");
            response.put("status", "COMPLETED");
            response.put("message", "Auto-execute cycle triggered. Check logs for results.");
        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("reason", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/auto-execute/logs")
    public ResponseEntity<List<Map<String, Object>>> getAutoExecLogs() {
        return ResponseEntity.ok(autoExecService.getExecLogs());
    }

    @GetMapping("/live-positions")
    public ResponseEntity<Map<String, Object>> getLivePositions() {
        List<LivePosition> openPositions = livePositionRepo.findAllOpen();
        List<Map<String, Object>> posList = openPositions.stream().map(LivePosition::toMap).toList();
        return ResponseEntity.ok(Map.of("positions", posList, "count", posList.size()));
    }

    @GetMapping("/auto-execute/execute")
    public ResponseEntity<Map<String, Object>> executeOpportunity(
            @RequestParam Long opportunityId,
            @RequestParam(defaultValue = "1") int multiplier) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("opportunityId", opportunityId);
        resp.put("multiplier", multiplier);
        try {
            historyService.getRepository().findById(opportunityId).ifPresentOrElse(opp -> {
                autoExecService.evaluateAndExecute(List.of(opp));
                resp.put("status", "SUBMITTED");
                resp.put("message", "Opportunity submitted to auto-exec evaluator");
            }, () -> {
                resp.put("status", "NOT_FOUND");
                resp.put("message", "Opportunity not found");
            });
        } catch (Exception e) {
            resp.put("status", "ERROR");
            resp.put("message", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    private void triggerAutoExec() {
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(2);
            List<OptionArbOpportunity> recentOpps = historyService.getRepository()
                    .findByScanTimeBetween(since, LocalDateTime.now());
            if (!recentOpps.isEmpty()) {
                autoExecService.evaluateAndExecute(recentOpps);
            }
        } catch (Exception e) {
            log.error("Auto-exec trigger failed: {}", e.getMessage());
        }
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
}
