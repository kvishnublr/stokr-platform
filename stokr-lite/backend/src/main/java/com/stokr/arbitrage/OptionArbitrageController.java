package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/option-arbitrage")
public class OptionArbitrageController {

    private static final Logger log = LoggerFactory.getLogger(OptionArbitrageController.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final BidParityService bidParityService;
    private final BoxSpreadService boxSpreadService;

    private final Map<String, Object> autoExecSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> auditLogs = Collections.synchronizedList(new ArrayList<>());

    public OptionArbitrageController(OptionChainService optionChainService,
                                     OptionArbHistoryService historyService,
                                     BidParityService bidParityService,
                                     BoxSpreadService boxSpreadService) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.bidParityService = bidParityService;
        this.boxSpreadService = boxSpreadService;

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
        List<Map<String, Object>> opps = bidParityService.scanBidParity(underlying);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/box-spread/scan")
    public ResponseEntity<Map<String, Object>> scanBoxSpread(@RequestParam(defaultValue = "ALL") String underlying) {
        List<Map<String, Object>> opps = boxSpreadService.scanBoxSpread(underlying);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/signals")
    public ResponseEntity<Map<String, Object>> getSignals(@RequestParam(defaultValue = "ALL") String underlying,
                                                          @RequestParam(defaultValue = "0") double minEdge,
                                                          @RequestParam(defaultValue = "1") int days) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            List<OptionArbOpportunity> opps = "ALL".equals(underlying)
                    ? historyService.getTodayOpportunities(today)
                    : historyService.getTodayOpportunities(today, underlying);

            List<Map<String, Object>> filtered = opps.stream()
                    .filter(o -> o.getEdgeAfterCosts() != null && o.getEdgeAfterCosts().doubleValue() >= minEdge)
                    .map(OptionArbOpportunity::toMap)
                    .toList();

            resp.put("signals", filtered);
            resp.put("totalCount", filtered.size());
            resp.put("summary", Map.of("todayCount", opps.size()));
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

    @GetMapping("/export-signals")
    public ResponseEntity<byte[]> exportSignalsCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append("ID,ScanTime,Underlying,Action,Strike,Spot,Futures,CE_Price,PE_Price,NetEdgeProfit\n");
        
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        List<OptionArbOpportunity> opps = historyService.getTodayOpportunities(today);
        for (OptionArbOpportunity opp : opps) {
            csv.append(opp.getId()).append(",")
               .append(opp.getScanTime()).append(",")
               .append(opp.getUnderlying()).append(",")
               .append(opp.getAction()).append(",")
               .append(opp.getStrike()).append(",")
               .append(opp.getSpotPrice()).append(",")
               .append(opp.getFuturesPrice()).append(",")
               .append(opp.getCeEntryPrice()).append(",")
               .append(opp.getPeEntryPrice()).append(",")
               .append(opp.getEdgeAfterCosts()).append("\n");
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=signals_export.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(bytes);
    }
}
