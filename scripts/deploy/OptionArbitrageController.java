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
    private final CalendarSpreadService calendarSpreadService;
    private final VolSurfaceService volSurfaceService;

    private final ConcurrentHashMap<String, List<ArbitrageOpportunity>> scanCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> scanTimestamp = new ConcurrentHashMap<>();

    private static final double RISK_FREE_RATE = 0.065;
    private static final Set<String> ALL_UNDERLYINGS = Set.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY");

    public OptionArbitrageController(OptionChainService optionChainService,
                                      ZerodhaSpotPriceFetcher spotFetcher,
                                      OptionArbHistoryService historyService,
                                      CalendarSpreadService calendarSpreadService,
                                      VolSurfaceService volSurfaceService) {
        this.optionChainService = optionChainService;
        this.spotFetcher = spotFetcher;
        this.historyService = historyService;
        this.calendarSpreadService = calendarSpreadService;
        this.volSurfaceService = volSurfaceService;
    }

    private record UnderlyingConfig(String name, String spotKey, String futuresPrefix) {}

    private static final Map<String, UnderlyingConfig> CONFIGS = Map.of(
        "NIFTY", new UnderlyingConfig("NIFTY", "NSE:NIFTY 50", "NFO:NIFTY"),
        "BANKNIFTY", new UnderlyingConfig("BANKNIFTY", "NSE:NIFTY BANK", "NFO:BANKNIFTY"),
        "MIDCPNIFTY", new UnderlyingConfig("MIDCPNIFTY", "NSE:NIFTY MID SELECT", "NFO:MIDCPNIFTY"),
        "FINNIFTY", new UnderlyingConfig("FINNIFTY", "NSE:NIFTY FIN SERVICE", "NFO:FINNIFTY")
    );

    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(
            @RequestParam(defaultValue = "ALL") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();
        List<ArbitrageOpportunity> allOpportunities = new ArrayList<>();

        try {
            List<String> targets = resolveUnderlyings(underlying);

            for (String u : targets) {
                UnderlyingConfig cfg = CONFIGS.get(u);
                if (cfg == null) continue;

                double spot = spotFetcher.getSpotPrice(cfg.spotKey());
                if (spot <= 0) {
                    log.error("Could not get {} spot price ({}), skipping", u, cfg.spotKey());
                    continue;
                }

                String futKey = cfg.futuresPrefix() + getCurrentMonthSuffix() + "FUT";
                double fut = spotFetcher.getSpotPrice(futKey);
                double premiumEst = spot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);
                double futLow = spot - premiumEst * 3;
                double futHigh = spot + premiumEst * 5;

                if (fut <= 0 || fut < futLow || fut > futHigh) {
                    log.warn("{} futures {} outside [{}, {}], using synthetic", u, fut, futLow, futHigh);
                    fut = spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
                }

                List<ArbitrageOpportunity> opps = optionChainService.scanOptionChain(u, spot, fut);
                allOpportunities.addAll(opps);
                scanCache.put(u, opps);
                scanTimestamp.put(u, System.currentTimeMillis());
                historyService.saveOpportunities(opps, u);
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

    @GetMapping("/calendar-spread")
    public ResponseEntity<Map<String, Object>> calendarSpread(
            @RequestParam(defaultValue = "ALL") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> allSpreads = new ArrayList<>();

        try {
            List<String> targets = resolveUnderlyings(underlying);

            for (String u : targets) {
                UnderlyingConfig cfg = CONFIGS.get(u);
                if (cfg == null) continue;

                double spot = spotFetcher.getSpotPrice(cfg.spotKey());
                if (spot <= 0) continue;

                String futKey = cfg.futuresPrefix() + getCurrentMonthSuffix() + "FUT";
                double fut = spotFetcher.getSpotPrice(futKey);
                if (fut <= 0 || Math.abs(fut - spot) > spot * 0.1) {
                    fut = spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
                }

                List<Map<String, Object>> spreads = calendarSpreadService.scanCalendarSpreads(u, spot, fut);
                allSpreads.addAll(spreads);
            }

            allSpreads.sort((a, b) -> {
                double e1 = a.containsKey("edgeAfterCosts") ? ((Number) a.get("edgeAfterCosts")).doubleValue() : 0;
                double e2 = b.containsKey("edgeAfterCosts") ? ((Number) b.get("edgeAfterCosts")).doubleValue() : 0;
                return Double.compare(e2, e1);
            });

            response.put("status", "ok");
            response.put("totalSpreads", allSpreads.size());
            response.put("spreads", allSpreads);

        } catch (Exception e) {
            log.error("Error scanning calendar spreads: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/vol-surface")
    public ResponseEntity<Map<String, Object>> volSurface(
            @RequestParam(defaultValue = "NIFTY") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            UnderlyingConfig cfg = CONFIGS.get(underlying);
            if (cfg == null) {
                response.put("status", "error");
                response.put("error", "Unknown underlying: " + underlying);
                return ResponseEntity.ok(response);
            }

            double spot = spotFetcher.getSpotPrice(cfg.spotKey());
            if (spot <= 0) {
                response.put("status", "error");
                response.put("error", "Could not get spot price for " + underlying);
                return ResponseEntity.ok(response);
            }

            String futKey = cfg.futuresPrefix() + getCurrentMonthSuffix() + "FUT";
            double fut = spotFetcher.getSpotPrice(futKey);
            if (fut <= 0 || Math.abs(fut - spot) > spot * 0.1) {
                fut = spot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
            }

            response = new LinkedHashMap<>(volSurfaceService.getVolSurface(underlying, spot, fut));

        } catch (Exception e) {
            log.error("Error getting vol surface: {}", e.getMessage(), e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/opportunities")
    public ResponseEntity<Map<String, Object>> getCachedOpportunities(
            @RequestParam(defaultValue = "ALL") String underlying) {

        Map<String, Object> response = new LinkedHashMap<>();
        List<ArbitrageOpportunity> all = new ArrayList<>();

        List<String> targets = resolveUnderlyings(underlying);
        for (String u : targets) {
            all.addAll(scanCache.getOrDefault(u, Collections.emptyList()));
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
        response.put("supportedUnderlyings", List.of("NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY"));
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

    private List<String> resolveUnderlyings(String param) {
        if ("ALL".equalsIgnoreCase(param) || "BOTH".equalsIgnoreCase(param)) {
            return new ArrayList<>(ALL_UNDERLYINGS);
        }
        String upper = param.toUpperCase();
        if (ALL_UNDERLYINGS.contains(upper)) {
            return List.of(upper);
        }
        return List.of("NIFTY");
    }

    private String getCurrentMonthSuffix() {
        java.time.YearMonth ym = java.time.YearMonth.now();
        String month = ym.getMonth().name().substring(0, 3);
        return String.format("%02d%s", ym.getYear() % 100, month);
    }
}
