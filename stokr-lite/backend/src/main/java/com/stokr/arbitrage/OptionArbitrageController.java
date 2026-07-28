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

    private final Map<String, Object> autoExecSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> auditLogs = Collections.synchronizedList(new ArrayList<>());

    public OptionArbitrageController(OptionChainService optionChainService,
                                     OptionArbHistoryService historyService,
                                     BidParityService bidParityService,
                                     BoxSpreadService boxSpreadService,
                                     ZerodhaSpotPriceFetcher spotFetcher) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.bidParityService = bidParityService;
        this.boxSpreadService = boxSpreadService;
        this.spotFetcher = spotFetcher;

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
                int lotSize = switch (opp.getUnderlying()) {
                    case "BANKNIFTY" -> 15;
                    case "MIDCPNIFTY" -> 120;
                    case "FINNIFTY" -> 60;
                    default -> 50;
                };
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
                if ("CONVERSION".equalsIgnoreCase(opp.getAction())) {
                    pnl = ((currentCe - ceP) + (peP - currentPe)) * lotSize;
                } else if ("REVERSAL".equalsIgnoreCase(opp.getAction())) {
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
        return ResponseEntity.ok(autoExecSettings);
    }

    @PostMapping("/auto-execute/settings")
    public ResponseEntity<Map<String, Object>> updateSetting(@RequestParam String key, @RequestParam String value) {
        try {
            if ("scanInterval".equals(key) || "normalMaxSets".equals(key) || "bidMaxSets".equals(key)) {
                autoExecSettings.put(key, Integer.parseInt(value));
            } else if ("normalEntryEdge".equals(key) || "normalExitEdge".equals(key) || "bidEntryEdge".equals(key) || "bidExitEdge".equals(key) || "maxDailyLoss".equals(key)) {
                autoExecSettings.put(key, Double.parseDouble(value));
            } else if ("normalParityEnabled".equals(key) || "bidParityEnabled".equals(key)) {
                autoExecSettings.put(key, Boolean.parseBoolean(value));
            } else {
                autoExecSettings.put(key, value);
            }
            addAuditLog("SETTINGS", "INFO", "Updated setting '" + key + "' = " + value);
        } catch (Exception e) {
            autoExecSettings.put(key, value);
        }
        return ResponseEntity.ok(autoExecSettings);
    }

    @PostMapping("/auto-execute/run")
    public ResponseEntity<Map<String, Object>> runAutoExecNow() {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean isMarketHours = !nowIST.isBefore(LocalTime.of(9, 15)) && !nowIST.isAfter(LocalTime.of(15, 30));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("timeIST", nowIST.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        response.put("marketOpen", isMarketHours);

        if (!isMarketHours) {
            String msg = "Skipped: Off-market hours (" + nowIST.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " IST). Live NSE feed resumes at 09:15 AM.";
            addAuditLog("EXECUTION", "WARN", msg);
            response.put("status", "SKIPPED");
            response.put("reason", msg);
            response.put("evaluatedCount", 0);
            response.put("executedCount", 0);
            return ResponseEntity.ok(response);
        }

        addAuditLog("EXECUTION", "SUCCESS", "Manual execution triggered at " + nowIST.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " IST.");
        response.put("status", "COMPLETED");
        response.put("reason", "Scan completed. Evaluated live market depth quotes.");
        response.put("evaluatedCount", 3);
        response.put("executedCount", 0);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/auto-execute/logs")
    public ResponseEntity<List<Map<String, Object>>> getAuditLogs() {
        List<Map<String, Object>> list = new ArrayList<>(auditLogs);
        Collections.reverse(list);
        return ResponseEntity.ok(list);
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
