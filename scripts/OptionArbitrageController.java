package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/option-arbitrage")
public class OptionArbitrageController {

    private static final Logger log = LoggerFactory.getLogger(OptionArbitrageController.class);

    private final OptionChainService optionChainService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbHistoryService historyService;

    private final ConcurrentHashMap<String, List<ArbitrageOpportunity>> scanCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> scanTimestamp = new ConcurrentHashMap<>();

    private static final double RISK_FREE_RATE = 0.065;

    public OptionArbitrageController(OptionChainService optionChainService,
                                      ZerodhaSpotPriceFetcher spotFetcher,
                                      OptionArbHistoryService historyService) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.historyService = historyService;
    }

    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(
            @RequestParam(defaultValue = "BOTH") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();
        List<ArbitrageOpportunity> allOpportunities = new ArrayList<>();

        try {
            if ("NIFTY".equalsIgnoreCase(underlying) || "BOTH".equalsIgnoreCase(underlying)) {
                double niftySpot = spotFetcher.getSpotPrice("NSE:NIFTY 50");
                double niftyFut = spotFetcher.getSpotPrice("NFO:NIFTY26JULFUT");
                double futPremiumEstimate = niftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);
                double expectedFutLow = niftySpot - futPremiumEstimate * 3;
                double expectedFutHigh = niftySpot + futPremiumEstimate * 5;
                if (niftyFut <= 0 || niftyFut < expectedFutLow || niftyFut > expectedFutHigh) {
                    log.warn("NIFTY futures {} outside expected range [{}, {}], using synthetic", niftyFut, expectedFutLow, expectedFutHigh);
                    niftyFut = niftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
                }
                if (niftySpot > 0) {
                    List<ArbitrageOpportunity> niftyOpps =
                        optionChainService.scanOptionChain("NIFTY", niftySpot, niftyFut);
                    allOpportunities.addAll(niftyOpps);
                    scanCache.put("NIFTY", niftyOpps);
                    scanTimestamp.put("NIFTY", System.currentTimeMillis());
                    historyService.saveOpportunities(niftyOpps, "NIFTY");
                } else {
                    log.error("Could not get NIFTY spot price, skipping scan");
                }
            }

            if ("BANKNIFTY".equalsIgnoreCase(underlying) || "BOTH".equalsIgnoreCase(underlying)) {
                double bankNiftySpot = spotFetcher.getSpotPrice("NSE:NIFTY BANK");
                double bankNiftyFut = spotFetcher.getSpotPrice("NFO:BANKNIFTY26JULFUT");
                double bankFutPremiumEstimate = bankNiftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);
                double bankExpectedFutLow = bankNiftySpot - bankFutPremiumEstimate * 3;
                double bankExpectedFutHigh = bankNiftySpot + bankFutPremiumEstimate * 5;
                if (bankNiftyFut <= 0 || bankNiftyFut < bankExpectedFutLow || bankNiftyFut > bankExpectedFutHigh) {
                    log.warn("BANKNIFTY futures {} outside expected range [{}, {}], using synthetic", bankNiftyFut, bankExpectedFutLow, bankExpectedFutHigh);
                    bankNiftyFut = bankNiftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
                }
                if (bankNiftySpot > 0) {
                    List<ArbitrageOpportunity> bankNiftyOpps =
                        optionChainService.scanOptionChain("BANKNIFTY", bankNiftySpot, bankNiftyFut);
                    allOpportunities.addAll(bankNiftyOpps);
                    scanCache.put("BANKNIFTY", bankNiftyOpps);
                    scanTimestamp.put("BANKNIFTY", System.currentTimeMillis());
                    historyService.saveOpportunities(bankNiftyOpps, "BANKNIFTY");
                } else {
                    log.error("Could not get BANKNIFTY spot price, skipping scan");
                }
            }

            allOpportunities.sort((a, b) -> Double.compare(b.confidence, a.confidence));

            response.put("status", "ok");
            response.put("timestamp", System.currentTimeMillis());
            response.put("totalOpportunities", allOpportunities.size());

            List<Map<String, Object>> oppMaps = new ArrayList<>();
            for (ArbitrageOpportunity opp : allOpportunities) {
                oppMaps.add(opp.toMap());
            }
            response.put("opportunities", oppMaps);

            Map<String, Long> typeSummary = new LinkedHashMap<>();
            for (ArbitrageOpportunity opp : allOpportunities) {
                typeSummary.merge(opp.type, 1L, Long::sum);
            }
            response.put("summary", typeSummary);

        } catch (Exception e) {
            log.error("Error scanning option arbitrage: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/opportunities")
    public ResponseEntity<Map<String, Object>> getCachedOpportunities(
            @RequestParam(defaultValue = "BOTH") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();
        List<ArbitrageOpportunity> all = new ArrayList<>();

        if ("NIFTY".equalsIgnoreCase(underlying) || "BOTH".equalsIgnoreCase(underlying)) {
            all.addAll(scanCache.getOrDefault("NIFTY", Collections.emptyList()));
        }
        if ("BANKNIFTY".equalsIgnoreCase(underlying) || "BOTH".equalsIgnoreCase(underlying)) {
            all.addAll(scanCache.getOrDefault("BANKNIFTY", Collections.emptyList()));
        }

        all.sort((a, b) -> Double.compare(b.confidence, a.confidence));

        response.put("status", "ok");
        response.put("totalOpportunities", all.size());
        List<Map<String, Object>> oppMaps = new ArrayList<>();
        for (ArbitrageOpportunity opp : all) {
            oppMaps.add(opp.toMap());
        }
        response.put("opportunities", oppMaps);

        Map<String, Long> timestamps = new LinkedHashMap<>();
        scanTimestamp.forEach((k, v) -> timestamps.put(k + "_lastScan", v));
        response.put("lastScan", timestamps);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("service", "OptionArbitrageScanner");
        response.put("scannerReady", true);
        response.put("tokenStatus", spotFetcher.getAuthToken() != null ? "valid" : "missing");
        response.put("settings", Map.of(
            "minParityDeviation", 15,
            "minEdgeAfterCosts", 300,
            "maxSpreadPct", 5.0,
            "cooldownSeconds", 60,
            "riskFreeRate", "6.5%"
        ));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            var opportunities = historyService.getHistory(page, size);
            response.put("status", "ok");
            response.put("page", page);
            response.put("size", size);
            response.put("totalPages", opportunities.getTotalPages());
            response.put("totalElements", opportunities.getTotalElements());

            List<Map<String, Object>> oppMaps = new ArrayList<>();
            for (var opp : opportunities.getContent()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", opp.getId());
                m.put("scanTime", opp.getScanTime() != null ? opp.getScanTime().toString() : null);
                m.put("underlying", opp.getUnderlying());
                m.put("type", opp.getType());
                m.put("strike", opp.getStrike());
                m.put("action", opp.getAction());
                m.put("legs", opp.getLegs());
                m.put("description", opp.getDescription());
                m.put("spotPrice", opp.getSpotPrice());
                m.put("futuresPrice", opp.getFuturesPrice());
                m.put("ceEntryPrice", opp.getCeEntryPrice());
                m.put("peEntryPrice", opp.getPeEntryPrice());
                m.put("edgePoints", opp.getEdgePoints());
                m.put("edgeAfterCosts", opp.getEdgeAfterCosts());
                m.put("confidence", opp.getConfidence());
                m.put("daysToExpiry", opp.getDaysToExpiry());
                m.put("expiryDate", opp.getExpiryDate() != null ? opp.getExpiryDate().toString() : null);
                m.put("status", opp.getStatus());
                m.put("ceExitPrice", opp.getCeExitPrice());
                m.put("peExitPrice", opp.getPeExitPrice());
                m.put("pnlPoints", opp.getPnlPoints());
                m.put("pnlAmount", opp.getPnlAmount());
                m.put("pnlAfterCosts", opp.getPnlAfterCosts());
                m.put("exitTime", opp.getExitTime() != null ? opp.getExitTime().toString() : null);
                m.put("notes", opp.getNotes());
                oppMaps.add(m);
            }
            response.put("opportunities", oppMaps);
        } catch (Exception e) {
            log.error("Error fetching history: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/summary")
    public ResponseEntity<Map<String, Object>> getDailySummary(
            @RequestParam(required = false) String date) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            LocalDate targetDate = date != null ? LocalDate.parse(date) : LocalDate.now();
            Map<String, Object> summary = historyService.getDailySummary(targetDate);
            response.put("status", "ok");
            response.putAll(summary);
        } catch (Exception e) {
            log.error("Error fetching summary: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history/dates")
    public ResponseEntity<Map<String, Object>> getAvailableDates(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            List<LocalDate> dates = historyService.getAvailableDates(days);
            response.put("status", "ok");
            response.put("dates", dates);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return ResponseEntity.ok(response);
    }
}
