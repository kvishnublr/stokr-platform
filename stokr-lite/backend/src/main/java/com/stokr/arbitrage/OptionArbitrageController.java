package com.stokr.arbitrage;
import org.springframework.beans.factory.annotation.Autowired;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import com.stokr.auth.AuthUser;

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

    private static class SwrCacheEntry {
        long timestamp;
        ResponseEntity<Map<String, Object>> response;
        volatile boolean isUpdating;
        SwrCacheEntry(long t, ResponseEntity<Map<String, Object>> r) { timestamp = t; response = r; isUpdating = false; }
    }
    private final ConcurrentHashMap<String, SwrCacheEntry> topPicksCache = new ConcurrentHashMap<>();

    private static final Logger log = LoggerFactory.getLogger(OptionArbitrageController.class);

    private final OptionChainService optionChainService;
    private final OptionArbHistoryService historyService;
    private final BidParityService bidParityService;
    private final BoxSpreadService boxSpreadService;
    private final VerticalSpreadService verticalSpreadService;
    private final ButterflySpreadService butterflySpreadService;
    private final CondorSpreadService condorSpreadService;
    private final CalendarSpreadService calendarSpreadService;
    private final IVRankService ivRankService;
    private final SyntheticFuturesArbService syntheticArbService;
    private final VolSurfaceService volSurfaceService;
    private final ZerodhaSpotPriceFetcher spotFetcher;
    private final OptionArbAutoExecService autoExecService;
    @Autowired private MultiTenantExecutionRouter executionRouter;
    private final LivePositionRepository livePositionRepo;
    private final OptionArbOpportunityRepository oppRepo;
    private final com.stokr.delivery.CashScannerService cashScannerService;
    private final com.stokr.delivery.CashExecutionService cashExecutionService;
    private final AutoRollService autoRollService;
    private final CandidateSnapshotRepository candidateSnapshotRepo;
    private final com.stokr.broker.BrokerAccountRepository brokerAccountRepo;
    private final com.stokr.broker.BrokerService brokerService;

    private final Map<String, Object> autoExecSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> auditLogs = Collections.synchronizedList(new ArrayList<>());

    public OptionArbitrageController(OptionChainService optionChainService,
                                     OptionArbHistoryService historyService,
                                     BidParityService bidParityService,
                                     BoxSpreadService boxSpreadService,
                                     VerticalSpreadService verticalSpreadService,
                                     ButterflySpreadService butterflySpreadService,
                                     CondorSpreadService condorSpreadService,
                                     CalendarSpreadService calendarSpreadService,
                                     IVRankService ivRankService,
                                     SyntheticFuturesArbService syntheticArbService,
                                     VolSurfaceService volSurfaceService,
                                     ZerodhaSpotPriceFetcher spotFetcher,
                                     OptionArbAutoExecService autoExecService,
                                     LivePositionRepository livePositionRepo,
                                     OptionArbOpportunityRepository oppRepo,
                                     com.stokr.delivery.CashScannerService cashScannerService,
                                     com.stokr.delivery.CashExecutionService cashExecutionService,
                                     AutoRollService autoRollService,
                                     CandidateSnapshotRepository candidateSnapshotRepo,
                                     com.stokr.broker.BrokerAccountRepository brokerAccountRepo,
                                     com.stokr.broker.BrokerService brokerService) {
        this.optionChainService = optionChainService;
        this.historyService = historyService;
        this.bidParityService = bidParityService;
        this.boxSpreadService = boxSpreadService;
        this.verticalSpreadService = verticalSpreadService;
        this.butterflySpreadService = butterflySpreadService;
        this.condorSpreadService = condorSpreadService;
        this.calendarSpreadService = calendarSpreadService;
        this.ivRankService = ivRankService;
        this.syntheticArbService = syntheticArbService;
        this.volSurfaceService = volSurfaceService;
        this.spotFetcher = spotFetcher;
        this.autoExecService = autoExecService;
        this.livePositionRepo = livePositionRepo;
        this.oppRepo = oppRepo;
        this.cashScannerService = cashScannerService;
        this.cashExecutionService = cashExecutionService;
        this.autoRollService = autoRollService;
        this.candidateSnapshotRepo = candidateSnapshotRepo;
        this.brokerAccountRepo = brokerAccountRepo;
        this.brokerService = brokerService;

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

    /** Marks each opportunity map with whether an OPEN position (paper or live) already exists
     *  for it, so the UI can warn "you already hold this" -- without blocking re-entry, since
     *  the manual Trade button must always still reach the broker. */
    private void markExistingPositions(List<Map<String, Object>> opps) {
        if (opps == null || opps.isEmpty()) return;
        List<Long> ids = opps.stream()
                .map(m -> m.get("id"))
                .filter(id -> id instanceof Number)
                .map(id -> ((Number) id).longValue())
                .collect(Collectors.toList());
        if (ids.isEmpty()) return;
        Map<Long, String> openBrokerByOppId = livePositionRepo.findOpenByOpportunityIdIn(ids).stream()
                .collect(Collectors.toMap(LivePosition::getOpportunityId, p -> p.getBroker() != null ? p.getBroker() : "PAPER",
                        (a, b) -> b));
        for (Map<String, Object> m : opps) {
            Object idObj = m.get("id");
            if (!(idObj instanceof Number)) continue;
            String existingBroker = openBrokerByOppId.get(((Number) idObj).longValue());
            m.put("existingOpenPosition", existingBroker != null);
            if (existingBroker != null) m.put("existingPositionBroker", existingBroker);
        }
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
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.error("Auto-exec from bid-parity scan failed: ", e); }
        }
        markExistingPositions(opps);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/box-spread/scan")
    public ResponseEntity<Map<String, Object>> scanBoxSpread(@RequestParam(defaultValue = "ALL") String underlying,
                                                               @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
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
        markExistingPositions(opps);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/box-spread/near-miss")
    public ResponseEntity<Map<String, Object>> scanBoxNearMiss(@RequestParam(defaultValue = "ALL") String underlying,
                                                                  @RequestParam(defaultValue = "0.15") double maxGapPct,
                                                                  @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "nearMisses", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> nearMisses = boxSpreadService.scanNearMiss(underlying, maxGapPct);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("nearMisses", nearMisses);
        resp.put("count", nearMisses.size());
        resp.put("note", "Watchlist tool -- these are NOT arbitrage yet. A box's payoff is fixed regardless of settlement, so any box priced below width is already genuine arbitrage and shown in the main scan; this list is combos close to crossing that line.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/vertical-spread/scan")
    public ResponseEntity<Map<String, Object>> scanVerticalSpread(@RequestParam(defaultValue = "ALL") String underlying,
                                                                    @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> opps = verticalSpreadService.scanVerticalSpread(underlying);
        if (opps != null && !opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.debug("Auto-exec from vertical-spread scan failed: {}", e.getMessage()); }
        }
        markExistingPositions(opps);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/vertical-spread/candidates")
    public ResponseEntity<Map<String, Object>> scanVerticalCandidates(@RequestParam(defaultValue = "ALL") String underlying,
                                                                         @RequestParam(defaultValue = "0.35") double maxCostRatio,
                                                                         @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "candidates", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> candidates = verticalSpreadService.scanCandidates(underlying, maxCostRatio);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("candidates", candidates);
        resp.put("count", candidates.size());
        resp.put("note", "Discovery/evaluation tool -- these are NOT arbitrage and have no backtested win rate.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/butterfly-spread/scan")
    public ResponseEntity<Map<String, Object>> scanButterflySpread(@RequestParam(defaultValue = "ALL") String underlying,
                                                                     @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> opps = butterflySpreadService.scanButterflySpread(underlying);
        if (opps != null && !opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.debug("Auto-exec from butterfly-spread scan failed: {}", e.getMessage()); }
        }
        markExistingPositions(opps);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/butterfly-spread/candidates")
    public ResponseEntity<Map<String, Object>> scanButterflyCandidates(@RequestParam(defaultValue = "ALL") String underlying,
                                                                          @RequestParam(defaultValue = "0.35") double maxCostRatio,
                                                                          @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "candidates", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> candidates = butterflySpreadService.scanCandidates(underlying, maxCostRatio);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("candidates", candidates);
        resp.put("count", candidates.size());
        resp.put("note", "Discovery/evaluation tool -- these are NOT arbitrage and have no backtested win rate.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/condor-spread/scan")
    public ResponseEntity<Map<String, Object>> scanCondorSpread(@RequestParam(defaultValue = "ALL") String underlying,
                                                                  @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> opps = condorSpreadService.scanCondorSpread(underlying);
        if (opps != null && !opps.isEmpty()) {
            try { autoExecService.evaluateAndExecuteFromMaps(opps); } catch (Exception e) { log.debug("Auto-exec from condor-spread scan failed: {}", e.getMessage()); }
        }
        markExistingPositions(opps);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("opportunities", opps);
        resp.put("count", opps.size());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/condor-spread/candidates")
    public ResponseEntity<Map<String, Object>> scanCondorCandidates(@RequestParam(defaultValue = "ALL") String underlying,
                                                                        @RequestParam(defaultValue = "0.35") double maxCostRatio,
                                                                        @RequestParam(defaultValue = "false") boolean force) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!force && (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30)))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "candidates", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. NSE/NFO hours: Mon-Fri 09:15-15:30 IST."
            ));
        }
        List<Map<String, Object>> candidates = condorSpreadService.scanCandidates(underlying, maxCostRatio);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", false);
        resp.put("candidates", candidates);
        resp.put("count", candidates.size());
        resp.put("note", "Discovery/evaluation tool -- these are NOT arbitrage and have no backtested win rate.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/calendar/scan")
    public ResponseEntity<Map<String, Object>> scanCalendarSpread(
            @RequestParam(defaultValue = "ALL") String underlying) {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "reason", "Market closed. Calendar spread scanner runs 9:15 AM - 3:30 PM IST."
            ));
        }
        try {
            List<Map<String, Object>> opps = calendarSpreadService.scanCalendarSpreads(underlying);
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "opportunities", opps,
                "count", opps.size()
            ));
        } catch (Exception e) {
            log.error("Calendar scan failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "error", e.getMessage()
            ));
        }
    }


    @GetMapping("/iv-rank/current")
    public ResponseEntity<Map<String, Object>> getIVRankCurrent() {
        try {
            Map<String, Object> data = ivRankService.getAllIVData();
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("IV rank fetch failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/iv-rank/history")
    public ResponseEntity<List<Map<String, Object>>> getIVHistory(
            @RequestParam(defaultValue = "NIFTY") String underlying,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ivRankService.getIVHistory(underlying, days));
    }

    @GetMapping("/iv-rank/snapshot")
    public ResponseEntity<Map<String, Object>> recordIVSnapshot() {
        ivRankService.recordIVSnapshots();
        return ResponseEntity.ok(Map.of("status", "recorded", "timestamp", System.currentTimeMillis()));
    }

    @GetMapping("/synthetic-arb/scan")
    public ResponseEntity<Map<String, Object>> scanSyntheticArb(
            @RequestParam(defaultValue = "ALL") String underlying) {
        LocalTime nowIST = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(LocalTime.of(9, 15)) || nowIST.isAfter(LocalTime.of(15, 30))) {
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "marketClosed", true,
                "opportunities", Collections.emptyList(),
                "count", 0
            ));
        }
        try {
            List<Map<String, Object>> opps = syntheticArbService.scanSyntheticArb(underlying);
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "opportunities", opps,
                "count", opps.size()
            ));
        } catch (Exception e) {
            log.error("Synthetic arb scan failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                "timestamp", System.currentTimeMillis(),
                "underlying", underlying,
                "opportunities", Collections.emptyList(),
                "count", 0,
                "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/vol-surface/scan")
    public ResponseEntity<Map<String, Object>> scanVolSurface(
            @RequestParam(defaultValue = "NIFTY") String underlying) {
        try {
            String spotKey = switch (underlying.toUpperCase()) {
                case "BANKNIFTY" -> "NSE:NIFTY BANK";
                case "MIDCPNIFTY" -> "NSE:NIFTY MID SELECT";
                case "FINNIFTY" -> "NSE:NIFTY FIN SERVICE";
                default -> "NSE:NIFTY 50";
            };
            String futKey = FuturesKeyResolver.resolveFuturesKey(underlying, spotFetcher, spotKey);
            double[] sf = spotFetcher.getSpotAndFutures(spotKey, futKey);
            double spot = (sf != null && sf.length > 0 && sf[0] > 0) ? sf[0] : 0;
            double fut = (sf != null && sf.length > 1 && sf[1] > 0) ? sf[1] : spot;
            Map<String, Object> surface = volSurfaceService.getVolSurface(underlying, spot, fut);
            surface.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(surface);
        } catch (Exception e) {
            log.error("Vol surface scan failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
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

            // Real fee schedule (STT/brokerage/exchange/SEBI/GST/stamp) instead of a flat
            // guess -- was previously a fictional flat Rs 200 regardless of premium/lot size.
            double sttPutSell = psBid * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            double sttPutBuy = pbAsk * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            double sttCallSell = csBid * lotSize * ArbitrageCosts.STT_OPTION_SELL;
            double sttCallBuy = cbAsk * lotSize * ArbitrageCosts.STT_OPTION_BUY;
            double stt = sttPutSell + sttPutBuy + sttCallSell + sttCallBuy;
            double brokerage = ArbitrageCosts.PER_LEG_BROKERAGE * 4;
            double turnover = (psBid + pbAsk + csBid + cbAsk) * lotSize;
            double exchange = turnover * ArbitrageCosts.EXCHANGE_RATE;
            double sebi = turnover * ArbitrageCosts.SEBI_RATE;
            double gst = (brokerage + exchange + sebi) * ArbitrageCosts.GST_RATE;
            double stamp = turnover * ArbitrageCosts.STAMP_RATE;
            double totalCosts = stt + brokerage + exchange + sebi + gst + stamp;
            double netEdge = credit * lotSize - totalCosts;

            if (riskReward >= 0.2 && netEdge > 0) {
                Map<String, Object> opp = new LinkedHashMap<>();
                
                double width = putSell - putBuy; // Wing width
                double widthMultiplier = width / step;
                String riskProfile = "HIGH";
                if (widthMultiplier >= 4) {
                    riskProfile = "LOW";
                } else if (widthMultiplier >= 2) {
                    riskProfile = "MEDIUM";
                }
                
                double estimatedMargin = 40000.0 * lotSize;
                double roiPct = (netEdge / estimatedMargin) * 100.0;
                
                opp.put("riskProfile", riskProfile);
                opp.put("roiPct", Math.round(roiPct * 100.0) / 100.0);
                opp.put("estimatedMargin", estimatedMargin);
                
                opp.put("type", "IRON_CONDOR");
                opp.put("underlying", underlying);
                opp.put("strike", putSell);
                opp.put("action", "SELL " + putSell + "PE/" + callSell + "CE | BUY " + putBuy + "PE/" + callBuy + "CE");
                opp.put("legs", String.format("SELL %d PE @ %.1f | BUY %d PE @ %.1f | SELL %d CE @ %.1f | BUY %d CE @ %.1f",
                    putSell, psBid, putBuy, pbAsk, callSell, csBid, callBuy, cbAsk));
                opp.put("credit", Math.round(credit * 100.0) / 100.0);
                opp.put("maxLoss", Math.round(maxLoss * 100.0) / 100.0);
                opp.put("riskReward", Math.round(riskReward * 100.0) / 100.0);
                opp.put("totalCosts", Math.round(totalCosts * 100.0) / 100.0);
                opp.put("edgeAfterCosts", Math.round(netEdge * 10.0) / 10.0);
                opp.put("expiry", expiry.toString());
                opp.put("lotSize", lotSize);
                opp.put("spotPrice", spot);
                opp.put("wingWidth", wingWidth * step);
                opp.put("confidence", Math.min(95, 60 + riskReward * 100));
                opp.put("legList", List.of(
                    ironLeg(putSell, "PE", "SELL", 1, psBid), ironLeg(putBuy, "PE", "BUY", 1, pbAsk),
                    ironLeg(callSell, "CE", "SELL", 1, csBid), ironLeg(callBuy, "CE", "BUY", 1, cbAsk)));
                results.add(opp);
            }
        }
        return results;
    }

    private static Map<String, Object> ironLeg(int strike, String optionType, String side, int qty, double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strike", strike);
        m.put("optionType", optionType);
        m.put("side", side);
        m.put("qty", qty);
        m.put("price", price);
        return m;
    }

    @GetMapping("/cash-surge/scan")
    public ResponseEntity<Map<String, Object>> scanCashSurge(
            @RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        try {
            List<Map<String, Object>> opps = cashScannerService.scanCashSurge();
            resp.put("opportunities", opps);
            resp.put("count", opps.size());
            if (opps.isEmpty()) {
                resp.put("message", "No delivery/volume surge setups in the latest EOD data.");
            }
        } catch (Exception e) {
            log.error("Cash surge scan failed: {}", e.getMessage());
            resp.put("opportunities", Collections.emptyList());
            resp.put("count", 0);
            resp.put("message", "Cash surge scan failed: " + e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/cash-momentum/scan")
    public ResponseEntity<Map<String, Object>> scanCashMomentum(
            @RequestParam(defaultValue = "ALL") String underlying) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        try {
            List<Map<String, Object>> opps = cashScannerService.scanCashSwing();
            resp.put("opportunities", opps);
            resp.put("count", opps.size());
            if (opps.isEmpty()) {
                resp.put("message", "No RSI 60-68 swing setups with sustained delivery accumulation right now.");
            }
        } catch (Exception e) {
            log.error("Cash swing scan failed: {}", e.getMessage());
            resp.put("opportunities", Collections.emptyList());
            resp.put("count", 0);
            resp.put("message", "Cash swing scan failed: " + e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    @PostMapping(value = "/cash-trade/execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> executeCashTrade(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        String strategyType = (String) body.getOrDefault("strategyType", "CASH_SURGE");
        double targetPrice = body.get("targetPrice") instanceof Number n ? n.doubleValue() : 0;
        double stopLossPrice = body.get("stopLossPrice") instanceof Number n ? n.doubleValue() : 0;
        String broker = (String) body.getOrDefault("broker", "PAPER");
        double capital = body.get("capital") instanceof Number n ? n.doubleValue() : 25000.0;
        Map<String, Object> result = cashExecutionService.execute(symbol, strategyType, targetPrice, stopLossPrice, broker, capital);
        addAuditLog("CASH_TRADE", result.get("status") != null ? result.get("status").toString() : "ERROR",
            "Cash trade " + symbol + " via " + broker + ": " + result.get("message"));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/cash-history")
    public ResponseEntity<Map<String, Object>> getCashHistory() {
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> positions = cashExecutionService.getClosedPositions();
        resp.put("positions", positions);
        resp.put("count", positions.size());
        double totalPnl = positions.stream().mapToDouble(p -> {
            Object pnl = p.get("currentPnl");
            return pnl != null ? ((Number) pnl).doubleValue() : 0;
        }).sum();
        resp.put("totalPnl", Math.round(totalPnl));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/cash-positions")
    public ResponseEntity<Map<String, Object>> getCashPositions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<Map<String, Object>> positions = cashExecutionService.getOpenPositionsWithLivePnl();
        resp.put("positions", positions);
        resp.put("count", positions.size());
        double totalPnl = positions.stream().mapToDouble(p -> {
            Object pnl = p.get("currentPnl");
            return pnl != null ? ((Number) pnl).doubleValue() : 0;
        }).sum();
        resp.put("totalPnl", Math.round(totalPnl));
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
                        if (p.getLegs() != null) {
                            for (Map<String, Object> leg : p.getLegs()) {
                                Object sym = leg.get("symbol");
                                if (sym instanceof String s) symbols.add(s);
                            }
                        }
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

                    // Untraded RUNNING Box/Vertical/Butterfly/Condor Spread signals -- same
                    // mark-to-market simulation idea as Bid Parity above, generalized over
                    // each opportunity's stored legList (no futures leg, 2-4 same-type legs).
                    Set<String> multiLegTypes = Set.of("BOX_SPREAD", "VERTICAL_SPREAD", "BUTTERFLY_SPREAD", "CONDOR_SPREAD", "IRON_CONDOR");
                    Map<Long, List<Map<String, Object>>> legSymbolsByOpp = new LinkedHashMap<>();
                    for (OptionArbOpportunity o : missingOppMap.values()) {
                        if ("RUNNING".equals(o.getStatus()) && multiLegTypes.contains(o.getStrategyType())
                                && o.getExpiryDate() != null && o.getLegList() != null) {
                            try {
                                List<Map<String, Object>> resolvedLegs = new ArrayList<>();
                                for (Map<String, Object> leg : o.getLegList()) {
                                    int strike = ((Number) leg.get("strike")).intValue();
                                    String optionType = (String) leg.get("optionType");
                                    String sym = optionChainService.buildNfoSymbol(o.getUnderlying(), o.getExpiryDate(), strike, optionType);
                                    Map<String, Object> resolved = new LinkedHashMap<>(leg);
                                    resolved.put("symbol", sym);
                                    resolvedLegs.add(resolved);
                                    if (sym != null) bpSymbols.add(sym);
                                }
                                legSymbolsByOpp.put(o.getId(), resolvedLegs);
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
                                } else if (pos.getLegs() != null && !pos.getLegs().isEmpty()) {
                                    int lotSz = pos.getLotSize() != null ? pos.getLotSize() : OptionChainService.getLotSize(pos.getUnderlying());
                                    int lotCount = pos.getLots() != null ? pos.getLots() : 1;
                                    double pnl = 0;
                                    for (Map<String, Object> leg : pos.getLegs()) {
                                        double entry = leg.get("price") instanceof Number n ? n.doubleValue() : 0;
                                        double exit = leg.get("exitPrice") instanceof Number n ? n.doubleValue() : 0;
                                        if (entry <= 0 || exit <= 0) continue;
                                        int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                                        String side = (String) leg.get("side");
                                        pnl += ("BUY".equals(side) ? (exit - entry) : (entry - exit)) * qtyMult;
                                    }
                                    pnl *= lotSz * lotCount;
                                    pnlMap.put(oppIdStr, Math.round(pnl));
                                    try {
                                        pos.setCurrentPnl(BigDecimal.valueOf(pnl));
                                        livePositionRepo.save(pos);
                                    } catch (Exception ignored) {}
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
                            } else if (pos.getLegs() != null && !pos.getLegs().isEmpty()) {
                                double pnl = autoExecService.computeMultiLegPnl(pos, quotes);
                                pnlMap.put(oppIdStr, Math.round(pnl));
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
                                        // EXPIRED means the contract expired with this signal NEVER traded --
                                        // nothing was ever captured, so it has no P&L, real or simulated. This
                                        // used to show the original detected edge in the P&L column, which
                                        // reads exactly like a realized win and was inflating the profit/win-
                                        // rate counts shown on the strategy summary cards with signals nobody
                                        // ever acted on. Null here (same as the MISSED case below) is the
                                        // honest answer: no P&L exists for a position that was never opened.
                                        if (opp.getExpiryDate() != null) {
                                            exitTimeMap.put(oppIdStr, opp.getExpiryDate().toString());
                                        }
                                        pnlMap.put(oppIdStr, null);
                                    } else if ("RUNNING".equals(oppStatus) && "BID_PARITY".equals(opp.getStrategyType())) {
                                        // RUNNING bid-parity signal, never traded — simulate live mark-to-market
                                        // P&L against current quotes and auto-exit once edge target is hit.
                                        String actionForClose = opp.getAction() != null ? opp.getAction().toUpperCase() : "";
                                        boolean ceIsLong = actionForClose.contains("BUY CE +");
                                        String ceSym = ceSymByOpp.get(oppId);
                                        String peSym = peSymByOpp.get(oppId);
                                        // Closing a long leg means SELLING it (get the bid); closing a short leg
                                        // means BUYING it back (pay the ask) -- using lastPrice here instead let
                                        // the simulated exit dodge the spread it would actually have to cross,
                                        // on top of ceEntryPrice/peEntryPrice previously making the same mistake
                                        // at entry (fixed in BidParityService) -- together those made every
                                        // never-actually-traded signal look like it converged to profit far
                                        // faster and more reliably than a real fill ever could.
                                        OptionChainService.OptionQuote ceQ = ceSym != null ? bpQuotes.get(ceSym) : null;
                                        OptionChainService.OptionQuote peQ = peSym != null ? bpQuotes.get(peSym) : null;
                                        double ceCurrent = ceQ != null ? (ceIsLong ? ceQ.bid : ceQ.ask) : 0;
                                        if (ceCurrent <= 0 && ceQ != null) ceCurrent = ceQ.lastPrice;
                                        double peCurrent = peQ != null ? (ceIsLong ? peQ.ask : peQ.bid) : 0;
                                        if (peCurrent <= 0 && peQ != null) peCurrent = peQ.lastPrice;

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

                                        boolean havePrices = ceCurrent > 0 || peCurrent > 0 || futCurrent > 0;
                                        double pnl = 0;
                                        if (havePrices) {
                                            if (ceIsLong) {
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
                                    } else if ("RUNNING".equals(oppStatus) && multiLegTypes.contains(opp.getStrategyType())
                                            && legSymbolsByOpp.containsKey(oppId)) {
                                        // RUNNING multi-leg spread signal, never traded — simulate live
                                        // mark-to-market P&L against current quotes and auto-exit once
                                        // the edge target is hit (same idea as BID_PARITY above).
                                        List<Map<String, Object>> resolvedLegs = legSymbolsByOpp.get(oppId);
                                        int lotSz = OptionChainService.getLotSize(opp.getUnderlying());
                                        boolean havePrices = false;
                                        double pnl = 0;
                                        for (Map<String, Object> leg : resolvedLegs) {
                                            String sym = (String) leg.get("symbol");
                                            String side = (String) leg.get("side");
                                            // Same fix as BID_PARITY above: closing a long leg (BUY) means
                                            // SELLING it back (get the bid), closing a short leg (SELL) means
                                            // BUYING it back (pay the ask) -- lastPrice here let the simulated
                                            // exit dodge the real spread cost, on never-actually-traded signals,
                                            // the same way it did for Bid Parity.
                                            OptionChainService.OptionQuote q = sym != null ? bpQuotes.get(sym) : null;
                                            double current = q != null ? ("BUY".equals(side) ? q.bid : q.ask) : 0;
                                            double entry = leg.get("price") instanceof Number n ? n.doubleValue() : 0;
                                            if (current <= 0 || entry <= 0) continue;
                                            havePrices = true;
                                            int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                                            pnl += ("BUY".equals(side) ? (current - entry) : (entry - current)) * qtyMult;
                                        }
                                        pnl *= lotSz;

                                        double edgeTarget = opp.getEdgeAfterCosts() != null ? opp.getEdgeAfterCosts().doubleValue() : 0;
                                        if (havePrices && edgeTarget > 0 && pnl >= edgeTarget) {
                                            opp.setStatus("EXITED");
                                            opp.setExitTime(LocalDateTime.now());
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
    public ResponseEntity<Map<String, Object>> getSettings(@RequestParam(defaultValue = "PAPER") String mode) {
        return ResponseEntity.ok(autoExecService.getSettings(mode));
    }

    /**
     * Per-strategy performance breakdown from ACTUALLY TRADED positions (live_positions), not
     * from signal-level simulations -- the Arbitrage Signals feed's win-rate counters include
     * never-traded signals marked to market, which is a different (and more flattering) number
     * than what real entries and exits produced. This answers "which strategies are actually
     * carrying their weight", split by paper vs live since mixing them hides whether a
     * strategy's record survived contact with a real broker.
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformance(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "ALL") String mode) {

        List<LivePosition> closed = livePositionRepo.findAllClosed().stream()
                .filter(p -> p.getCurrentPnl() != null)
                .filter(p -> {
                    if ("ALL".equalsIgnoreCase(mode)) return true;
                    String posBroker = p.getBroker() != null ? p.getBroker() : "PAPER";
                    if ("LIVE".equalsIgnoreCase(mode)) return !"PAPER".equalsIgnoreCase(posBroker); return mode.equalsIgnoreCase(posBroker);
                })
                .filter(p -> {
                    if (startDate == null || endDate == null || p.getEnteredAt() == null) return true;
                    LocalDate d = p.getEnteredAt().toLocalDate();
                    return !d.isBefore(LocalDate.parse(startDate)) && !d.isAfter(LocalDate.parse(endDate));
                })
                .toList();

        Map<String, List<LivePosition>> byStrategy = closed.stream()
                .collect(Collectors.groupingBy(p -> p.getStrategyType() != null ? p.getStrategyType() : "UNKNOWN"));

        List<Map<String, Object>> strategies = new ArrayList<>();
        for (Map.Entry<String, List<LivePosition>> e : byStrategy.entrySet()) {
            strategies.add(summarize(e.getKey(), e.getValue(), true));
        }
        strategies.sort((a, b) -> Integer.compare((Integer) b.get("trades"), (Integer) a.get("trades")));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("strategies", strategies);
        resp.put("overall", summarize("ALL", closed, false));
        resp.put("mode", mode);
        resp.put("note", "Computed from real closed positions only (entered and exited). Never-traded signals are excluded -- their simulated results are not trading performance.");
        return ResponseEntity.ok(resp);
    }

    private Map<String, Object> summarize(String label, List<LivePosition> rows, boolean includeUnderlyings) {
        int trades = rows.size();
        long wins = rows.stream().filter(p -> p.getCurrentPnl().doubleValue() > 0).count();
        long losses = rows.stream().filter(p -> p.getCurrentPnl().doubleValue() < 0).count();
        double total = rows.stream().mapToDouble(p -> p.getCurrentPnl().doubleValue()).sum();
        double best = rows.stream().mapToDouble(p -> p.getCurrentPnl().doubleValue()).max().orElse(0);
        double worst = rows.stream().mapToDouble(p -> p.getCurrentPnl().doubleValue()).min().orElse(0);

        // Average win and average loss separately: a strategy can show a strong win rate while
        // its rare losses dwarf its many small wins, which a single avg-P&L number hides.
        double avgWin = rows.stream().filter(p -> p.getCurrentPnl().doubleValue() > 0)
                .mapToDouble(p -> p.getCurrentPnl().doubleValue()).average().orElse(0);
        double avgLoss = rows.stream().filter(p -> p.getCurrentPnl().doubleValue() < 0)
                .mapToDouble(p -> p.getCurrentPnl().doubleValue()).average().orElse(0);

        double avgHoldMin = rows.stream()
                .filter(p -> p.getEnteredAt() != null && p.getExitedAt() != null)
                .mapToDouble(p -> java.time.Duration.between(p.getEnteredAt(), p.getExitedAt()).toSeconds() / 60.0)
                .average().orElse(0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategyType", label);
        m.put("trades", trades);
        m.put("wins", (int) wins);
        m.put("losses", (int) losses);
        m.put("winRate", trades > 0 ? Math.round(wins * 1000.0 / trades) / 10.0 : 0.0);
        m.put("totalPnl", Math.round(total));
        m.put("avgPnl", trades > 0 ? Math.round(total / trades) : 0);
        m.put("avgWin", Math.round(avgWin));
        m.put("avgLoss", Math.round(avgLoss));
        // Expectancy per trade -- the number that actually decides whether repeating this
        // strategy makes or loses money over time, unlike win rate on its own.
        m.put("expectancy", trades > 0 ? Math.round(total / trades) : 0);
        m.put("bestTrade", Math.round(best));
        m.put("worstTrade", Math.round(worst));
        m.put("avgHoldMinutes", Math.round(avgHoldMin * 10.0) / 10.0);

        if (includeUnderlyings) {
            Map<String, List<LivePosition>> byU = rows.stream()
                    .collect(Collectors.groupingBy(p -> p.getUnderlying() != null ? p.getUnderlying() : "UNKNOWN"));
            List<Map<String, Object>> uList = new ArrayList<>();
            for (Map.Entry<String, List<LivePosition>> ue : byU.entrySet()) {
                List<LivePosition> ur = ue.getValue();
                double uTotal = ur.stream().mapToDouble(p -> p.getCurrentPnl().doubleValue()).sum();
                long uWins = ur.stream().filter(p -> p.getCurrentPnl().doubleValue() > 0).count();
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("underlying", ue.getKey());
                um.put("trades", ur.size());
                um.put("wins", (int) uWins);
                um.put("winRate", ur.isEmpty() ? 0.0 : Math.round(uWins * 1000.0 / ur.size()) / 10.0);
                um.put("totalPnl", Math.round(uTotal));
                um.put("avgPnl", ur.isEmpty() ? 0 : Math.round(uTotal / ur.size()));
                uList.add(um);
            }
            uList.sort((a, b) -> Integer.compare((Integer) b.get("trades"), (Integer) a.get("trades")));
            m.put("underlyings", uList);
        }
        return m;
    }

    @GetMapping("/candidate-history")
    public ResponseEntity<Map<String, Object>> getCandidateHistory(
            @RequestParam String strategyType,
            @RequestParam(required = false) String underlying,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
        LocalDateTime end = LocalDate.parse(endDate).atTime(23, 59, 59);
        String u = (underlying == null || underlying.isBlank() || "ALL".equalsIgnoreCase(underlying)) ? null : underlying;
        List<CandidateSnapshot> rows = candidateSnapshotRepo.findInRange(strategyType, u, start, end);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("count", rows.size());
        resp.put("note", "Periodic snapshots (every 15 min, market hours) of the Candidates discovery scan -- count + top candidate per underlying, not every candidate shown.");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/auto-roll/pending")
    public ResponseEntity<Map<String, Object>> getPendingRolls() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pending", autoRollService.listPending());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/auto-roll/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirmRoll(@PathVariable Long id) {
        Map<String, Object> result = autoRollService.confirmRoll(id);
        addAuditLog("AUTO_ROLL", result.get("status") != null ? result.get("status").toString() : "ERROR",
            "Roll #" + id + " confirmed: " + result.get("message"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/auto-roll/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissRoll(@PathVariable Long id) {
        Map<String, Object> result = autoRollService.dismissRoll(id);
        addAuditLog("AUTO_ROLL", "INFO", "Roll #" + id + " dismissed");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/auto-execute/settings")
    public ResponseEntity<Map<String, Object>> updateSetting(@RequestParam String key, @RequestParam String value, @RequestParam(defaultValue = "PAPER") String mode) {
        try {
            autoExecService.updateSetting(mode, key, value);
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

    /**
     * Real positions straight from the broker's own portfolio API -- our own live_positions
     * table is only as accurate as our order-tracking logic; the user asked for something that
     * shows exactly what Zerodha itself reports as held, to cross-check against it directly
     * rather than trust our DB alone.
     */
    @GetMapping("/broker-positions")
    public ResponseEntity<Map<String, Object>> getBrokerPositions(@RequestParam(defaultValue = "ZERODHA") String broker) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if ("PAPER".equalsIgnoreCase(broker)) {
            resp.put("broker", broker);
            resp.put("positions", List.of());
            resp.put("note", "PAPER has no real broker positions to reconcile against.");
            return ResponseEntity.ok(resp);
        }
        try {
            List<com.stokr.broker.BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
            if (accounts.isEmpty()) {
                resp.put("broker", broker);
                resp.put("positions", List.of());
                resp.put("error", "No active " + broker + " account found");
                return ResponseEntity.ok(resp);
            }
            com.stokr.broker.BrokerAccount account = accounts.get(0);
            com.stokr.broker.BrokerAdapter adapter = brokerService.getAdapter(broker);
            List<com.stokr.broker.BrokerPosition> positions = adapter.getPositions(account.getAccessToken());
            
            List<String> symbols = positions.stream().map(com.stokr.broker.BrokerPosition::symbol).toList();
            Map<String, OptionChainService.OptionQuote> fetchedQuotes;
            try {
                fetchedQuotes = symbols.isEmpty() ? Map.of() : optionChainService.fetchQuotes(symbols);
            } catch (Exception e) {
                fetchedQuotes = Map.of();
            }
            final Map<String, OptionChainService.OptionQuote> quotes = fetchedQuotes;
            
            List<Map<String, Object>> enhancedPositions = positions.stream().map(p -> {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("symbol", p.symbol());
                map.put("exchange", p.exchange());
                map.put("quantity", p.quantity());
                map.put("qty", p.quantity()); // Alias for frontend
                map.put("avgPrice", p.avgPrice());
                OptionChainService.OptionQuote q = quotes.get(p.symbol());
                double ltp = q != null && q.lastPrice > 0 ? q.lastPrice : p.lastPrice().doubleValue();
                map.put("lastPrice", ltp);
                
                // Recompute unrealizedPnl based on the live LTP so it matches Kite Live precisely
                double liveUnrealizedPnl = (ltp - p.avgPrice().doubleValue()) * p.quantity();
                map.put("unrealizedPnl", liveUnrealizedPnl);
                
                map.put("realizedPnl", p.realizedPnl());
                map.put("productType", p.productType());
                double bid = q != null ? q.bid : p.lastPrice().doubleValue();
                double ask = q != null ? q.ask : p.lastPrice().doubleValue();
                if (bid <= 0) bid = p.lastPrice().doubleValue();
                if (ask <= 0) ask = p.lastPrice().doubleValue();
                map.put("bid", bid);
                map.put("ask", ask);
                
                double executablePnl = 0;
                if (p.quantity() > 0) {
                    executablePnl = (bid - p.avgPrice().doubleValue()) * p.quantity();
                } else if (p.quantity() < 0) {
                    executablePnl = (p.avgPrice().doubleValue() - ask) * Math.abs(p.quantity());
                }
                map.put("executablePnl", executablePnl);
                
                return map;
            }).toList();
            
            resp.put("broker", broker);
            resp.put("positions", enhancedPositions);
            resp.put("count", positions.size());
            resp.put("fetchedAt", System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Broker positions fetch failed for {}: {}", broker, e.getMessage());
            resp.put("broker", broker);
            resp.put("positions", List.of());
            resp.put("error", "Fetch failed: " + e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * Flattens every position into individual leg-level order rows -- Zerodha's own Orders page
     * shows one row per actual order (not per position), and this platform never had an
     * equivalent: the only order-level visibility was buried inside each position's expanded
     * breakdown. Emits one row per leg's entry order, plus a second synthetic row for the exit
     * once a position is closed (we don't persist a separate order id/status for the closing
     * leg today, only its fill price, so that row's status is inferred as COMPLETE from the
     * exit price being present -- everything else here reflects a real recorded order state).
     */
    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> getOrders(@RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "200") int limit) {
        List<LivePosition> positions = livePositionRepo.findAllByOrderByEnteredAtDesc();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (LivePosition p : positions) {
            String broker = p.getBroker() != null ? p.getBroker() : "PAPER";
            String product = "PAPER".equalsIgnoreCase(broker) ? "PAPER" : "NRML";
            boolean isMultiLeg = p.getLegs() != null && !p.getLegs().isEmpty();

            if (isMultiLeg) {
                for (Map<String, Object> leg : p.getLegs()) {
                    String legStatus = leg.get("status") instanceof String s && !s.isBlank() ? s : mapPositionStatus(p.getStatus());
                    rows.add(orderRow(p.getEnteredAt(), p.getUnderlying(), (String) leg.get("symbol"),
                            (String) leg.get("side"), toQty(leg.get("qty"), p.getLotSize(), p.getLots()),
                            leg.get("price"), product, broker, legStatus, (String) leg.get("orderId"), p.getErrorMessage()));
                    Object exitPrice = leg.get("exitPrice");
                    if (exitPrice != null && p.getExitedAt() != null) {
                        String closeSide = "BUY".equals(leg.get("side")) ? "SELL" : "BUY";
                        rows.add(orderRow(p.getExitedAt(), p.getUnderlying(), (String) leg.get("symbol"),
                                closeSide, toQty(leg.get("qty"), p.getLotSize(), p.getLots()),
                                exitPrice, product, broker, "COMPLETE", null, null, "EXIT"));
                    }
                }
            } else {
                String action = p.getAction() != null ? p.getAction().toUpperCase() : "";
                boolean buyCe = !action.contains("SELL CE +");
                // In BOTH conversion (SELL CE + BUY PE + BUY FUT) and reversal (BUY CE +
                // SELL PE + SELL FUT), the futures leg is always OPPOSITE the CE leg -- that's
                // what makes the structure delta-neutral. This previously assigned FUT the SAME
                // side as CE, so every futures row on this page showed the wrong direction
                // (e.g. "BUY FUT + SELL CE + BUY PE" rendered its futures leg as SELL).
                String ceSide = buyCe ? "BUY" : "SELL";
                String peSide = buyCe ? "SELL" : "BUY";
                String futSide = buyCe ? "SELL" : "BUY";
                String ceClose = buyCe ? "SELL" : "BUY";
                String peClose = buyCe ? "BUY" : "SELL";
                String futClose = buyCe ? "BUY" : "SELL";
                int qty = (p.getLotSize() != null ? p.getLotSize() : 1) * (p.getLots() != null ? p.getLots() : 1);
                String legStatus = mapPositionStatus(p.getStatus());
                if (p.getCeSymbol() != null) rows.add(orderRow(p.getEnteredAt(), p.getUnderlying(), p.getCeSymbol(),
                        ceSide, qty, p.getCeEntryPrice(), product, broker, legStatus, p.getCeOrderId(), p.getErrorMessage()));
                if (p.getPeSymbol() != null) rows.add(orderRow(p.getEnteredAt(), p.getUnderlying(), p.getPeSymbol(),
                        peSide, qty, p.getPeEntryPrice(), product, broker, legStatus, p.getPeOrderId(), p.getErrorMessage()));
                if (p.getFutSymbol() != null) rows.add(orderRow(p.getEnteredAt(), p.getUnderlying(), p.getFutSymbol(),
                        futSide, qty, p.getFutEntryPrice(), product, broker, legStatus, p.getFutOrderId(), p.getErrorMessage()));
                if (p.getExitedAt() != null && (p.getCeExitPrice() != null || p.getPeExitPrice() != null || p.getFutExitPrice() != null)) {
                    if (p.getCeSymbol() != null && p.getCeExitPrice() != null) rows.add(orderRow(p.getExitedAt(), p.getUnderlying(), p.getCeSymbol(),
                            ceClose, qty, p.getCeExitPrice(), product, broker, "COMPLETE", null, null, "EXIT"));
                    if (p.getPeSymbol() != null && p.getPeExitPrice() != null) rows.add(orderRow(p.getExitedAt(), p.getUnderlying(), p.getPeSymbol(),
                            peClose, qty, p.getPeExitPrice(), product, broker, "COMPLETE", null, null, "EXIT"));
                    if (p.getFutSymbol() != null && p.getFutExitPrice() != null) rows.add(orderRow(p.getExitedAt(), p.getUnderlying(), p.getFutSymbol(),
                            futClose, qty, p.getFutExitPrice(), product, broker, "COMPLETE", null, null, "EXIT"));
                }
            }
        }

        rows.sort((a, b) -> {
            String ta = (String) a.get("time"), tb = (String) b.get("time");
            if (ta == null || tb == null) return 0;
            return tb.compareTo(ta);
        });

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            rows = rows.stream().filter(r -> status.equalsIgnoreCase((String) r.get("status"))).collect(Collectors.toList());
        }
        if (rows.size() > limit) rows = rows.subList(0, limit);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orders", rows);
        resp.put("count", rows.size());
        return ResponseEntity.ok(resp);
    }

    private String mapPositionStatus(String positionStatus) {
        if (positionStatus == null) return "UNKNOWN";
        return switch (positionStatus) {
            case "OPEN", "CLOSED", "EXITED", "PARTIAL" -> "COMPLETE";
            case "FAILED", "REJECTED" -> "REJECTED";
            case "EXECUTING" -> "OPEN";
            default -> positionStatus;
        };
    }

    private int toQty(Object qtyMult, Integer lotSize, Integer lots) {
        int mult = qtyMult instanceof Number n ? n.intValue() : 1;
        int ls = lotSize != null ? lotSize : 1;
        int lt = lots != null ? lots : 1;
        return mult * ls * lt;
    }

    private Map<String, Object> orderRow(LocalDateTime time, String underlying, String symbol, String side,
                                          int qty, Object price, String product, String broker, String status, String orderId) {
        return orderRow(time, underlying, symbol, side, qty, price, product, broker, status, orderId, null, "ENTRY");
    }

    private Map<String, Object> orderRow(LocalDateTime time, String underlying, String symbol, String side,
                                          int qty, Object price, String product, String broker, String status,
                                          String orderId, String reason) {
        return orderRow(time, underlying, symbol, side, qty, price, product, broker, status, orderId, reason, "ENTRY");
    }

    private Map<String, Object> orderRow(LocalDateTime time, String underlying, String symbol, String side,
                                          int qty, Object price, String product, String broker, String status,
                                          String orderId, String reason, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", time != null ? time.toString() : null);
        // ENTRY vs EXIT: a position CLOSE emits its own leg rows, previously indistinguishable
        // from an opening order -- so closing a position after hours looked exactly like the
        // platform had opened brand-new trades post-market. The side is already flipped for an
        // exit (closing a BUY shows as SELL), which made it read even more like a real new order.
        row.put("kind", kind);
        row.put("underlying", underlying);
        row.put("symbol", symbol);
        row.put("side", side);
        row.put("qty", qty);
        double priceVal = price instanceof Number n ? n.doubleValue() : (price instanceof BigDecimal bd ? bd.doubleValue() : 0);
        row.put("price", Math.round(priceVal * 100.0) / 100.0);
        row.put("product", product);
        row.put("broker", broker);
        row.put("status", status);
        row.put("orderId", orderId);
        // The broker's real rejection text (e.g. Zerodha's "could not be converted to AMO",
        // "quantity should be multiple of 30") -- shown only for REJECTED rows since that's
        // where "why" actually matters; a raw status pill alone forces the user to go dig
        // through Auto-Exec logs or SSH into the server to find out what Zerodha actually said.
        if ("REJECTED".equalsIgnoreCase(status) && reason != null && !reason.isBlank()) {
            row.put("reason", reason);
        }
        return row;
    }

    /**
     * Cross-checks every OPEN live (non-paper) position against the broker's actual portfolio
     * before showing it as "live" -- a position that was closed manually at the broker (outside
     * this app) or auto-squared-off by broker RMS otherwise sits in our DB as OPEN forever,
     * with nothing to ever notice and correct it. Any leg symbol with a real nonzero quantity
     * at the broker means the position is genuinely still held; if NONE of a position's legs
     * show up, it's stale -- auto-close it here instead of showing phantom P&L on a position
     * that doesn't exist anymore. PAPER positions are untouched (no broker to check against).
     */
    private List<LivePosition> reconcileAgainstBroker(List<LivePosition> openPositions) {
        Set<String> liveBrokers = openPositions.stream()
                .map(LivePosition::getBroker)
                .filter(b -> b != null && !"PAPER".equalsIgnoreCase(b))
                .collect(Collectors.toSet());
        if (liveBrokers.isEmpty()) return openPositions;

        Map<String, Set<String>> heldSymbolsByBroker = new HashMap<>();
        for (String broker : liveBrokers) {
            try {
                List<com.stokr.broker.BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
                if (accounts.isEmpty()) continue;
                com.stokr.broker.BrokerAdapter adapter = brokerService.getAdapter(broker);
                List<com.stokr.broker.BrokerPosition> real = adapter.getPositions(accounts.get(0).getAccessToken());
                Set<String> held = real.stream()
                        .filter(p -> p.quantity() != 0)
                        .map(com.stokr.broker.BrokerPosition::symbol)
                        .collect(Collectors.toSet());
                heldSymbolsByBroker.put(broker, held);
            } catch (Exception e) {
                log.debug("Reconciliation fetch failed for {}, skipping stale-check this cycle: {}", broker, e.getMessage());
            }
        }
        if (heldSymbolsByBroker.isEmpty()) return openPositions;

        List<LivePosition> stillOpen = new ArrayList<>();
        for (LivePosition p : openPositions) {
            String broker = p.getBroker();
            if (broker == null || "PAPER".equalsIgnoreCase(broker) || !heldSymbolsByBroker.containsKey(broker)) {
                stillOpen.add(p);
                continue;
            }
            Set<String> held = heldSymbolsByBroker.get(broker);
            List<String> legSymbols = new ArrayList<>();
            if (p.getLegs() != null) {
                for (Map<String, Object> leg : p.getLegs()) {
                    Object sym = leg.get("symbol");
                    if (sym instanceof String s) legSymbols.add(s);
                }
            } else {
                if (p.getCeSymbol() != null) legSymbols.add(p.getCeSymbol());
                if (p.getPeSymbol() != null) legSymbols.add(p.getPeSymbol());
                if (p.getFutSymbol() != null) legSymbols.add(p.getFutSymbol());
            }
            boolean anyHeld = legSymbols.stream().anyMatch(held::contains);
            if (anyHeld || legSymbols.isEmpty()) {
                stillOpen.add(p);
            } else {
                p.setStatus("CLOSED");
                p.setExitedAt(LocalDateTime.now());
                p.setErrorMessage("Auto-corrected: broker showed no matching position (likely closed manually outside the app)");
                livePositionRepo.save(p);
                log.info("Reconciliation: auto-closed stale position {} ({} {}) -- broker has none of {}",
                        p.getId(), p.getUnderlying(), p.getStrike(), legSymbols);
            }
        }
        return stillOpen;
    }

    @GetMapping("/live-positions")
    public ResponseEntity<Map<String, Object>> getLivePositions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<LivePosition> openPositions = livePositionRepo.findAllOpen();
        openPositions = reconcileAgainstBroker(openPositions);

        Map<String, OptionChainService.OptionQuote> allQuotes;
        try {
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
            boolean isMultiLeg = p.getLegs() != null && !p.getLegs().isEmpty();
            int lotSize = p.getLotSize() != null ? p.getLotSize() : getLotSize(p.getUnderlying());
            int lots = p.getLots() != null ? p.getLots() : 1;

            double pnl;
            if (isMultiLeg) {
                pnl = autoExecService.computeMultiLegPnl(p, quotes);
                map.put("ceCurrent", 0);
                map.put("peCurrent", 0);
                map.put("futCurrent", 0);
            } else {
                double ceCurrent = 0, peCurrent = 0, futCurrent = 0;
                if (p.getCeSymbol() != null && quotes.containsKey(p.getCeSymbol())) ceCurrent = quotes.get(p.getCeSymbol()).lastPrice;
                if (p.getPeSymbol() != null && quotes.containsKey(p.getPeSymbol())) peCurrent = quotes.get(p.getPeSymbol()).lastPrice;
                if (p.getFutSymbol() != null && quotes.containsKey(p.getFutSymbol())) futCurrent = quotes.get(p.getFutSymbol()).lastPrice;

                double ceEntry = p.getCeEntryPrice() != null ? p.getCeEntryPrice().doubleValue() : 0;
                double peEntry = p.getPeEntryPrice() != null ? p.getPeEntryPrice().doubleValue() : 0;
                double futEntry = p.getFutEntryPrice() != null ? p.getFutEntryPrice().doubleValue() : 0;

                double legacyPnl = 0;
                String action = p.getAction() != null ? p.getAction().toUpperCase() : "";
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
                pnl = legacyPnl * lotSize * lots;
                map.put("ceCurrent", ceCurrent);
                map.put("peCurrent", peCurrent);
                map.put("futCurrent", futCurrent);
            }
            map.put("currentPnl", Math.round(pnl));

            double target = p.getTargetEdge() != null ? p.getTargetEdge().doubleValue() : 0;
            double pnlPerLot = lots > 0 ? Math.abs(pnl) / lots : 0;
            double edgeCaptured = target > 0 ? Math.min(100, Math.round(pnlPerLot / target * 100)) : 0;
            map.put("edgeCaptured", edgeCaptured);
            map.put("marketOpen", marketOpen);
            map.put("isMultiLeg", isMultiLeg);

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

    @PostMapping("/positions/{positionId}/exit")
    public ResponseEntity<Map<String, Object>> exitPosition(@PathVariable Long positionId) {
        Map<String, Object> result = autoExecService.manualExitPosition(positionId);
        if ("ERROR".equals(result.get("status"))) {
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
if (mode != null && !"ALL".equalsIgnoreCase(mode)) {            positions = positions.stream().filter(p -> {                String pb = p.getBroker() != null ? p.getBroker() : (p.getCeOrderId() != null && p.getCeOrderId().startsWith("PAPER") ? "PAPER" : "LIVE");                if ("LIVE".equalsIgnoreCase(mode)) return !"PAPER".equalsIgnoreCase(pb);                return mode.equalsIgnoreCase(pb);            }).toList();        }

        // Compute P&L for all positions
        List<String> symbols = new ArrayList<>();
        for (LivePosition p : positions) {
            if ("OPEN".equals(p.getStatus())) {
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
            boolean isMultiLeg = p.getLegs() != null && !p.getLegs().isEmpty();

            if ("CLOSED".equals(p.getStatus()) || "EXITED".equals(p.getStatus())) {
                // Use stored P&L or compute from exit prices
                if (p.getCurrentPnl() != null) {
                    pnl = p.getCurrentPnl().doubleValue();
                } else if (isMultiLeg) {
                    for (Map<String, Object> leg : p.getLegs()) {
                        double entry = leg.get("price") instanceof Number n ? n.doubleValue() : 0;
                        double exit = leg.get("exitPrice") instanceof Number n ? n.doubleValue() : 0;
                        if (entry <= 0 || exit <= 0) continue;
                        int qtyMult = leg.get("qty") instanceof Number n ? n.intValue() : 1;
                        String side = (String) leg.get("side");
                        pnl += ("BUY".equals(side) ? (exit - entry) : (entry - exit)) * qtyMult;
                    }
                    pnl *= lotSize * lots;
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
            } else if (isMultiLeg) {
                pnl = autoExecService.computeMultiLegPnl(p, q);
                m.put("ceCurrent", 0);
                m.put("peCurrent", 0);
                m.put("futCurrent", 0);
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

    /** Same fix as OptionArbAutoExecService.getLotSize -- this was its own hardcoded switch
     *  (NIFTY=25, BANKNIFTY=15, ...) independent of OptionChainService's dynamic Zerodha-fetched
     *  values, so it kept using stale numbers after the dynamic fetch was added elsewhere. */
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
    public ResponseEntity<Map<String, Object>> executePaperTrade(@RequestBody Map<String, Object> body, Authentication auth) {
        Long userId = 1L;
        if (auth != null && auth.getPrincipal() instanceof AuthUser) { userId = ((AuthUser)auth.getPrincipal()).getId(); }
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
                Object legListObj = body.get("legList");
                if (legListObj instanceof List<?> ll) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> cast = (List<Map<String, Object>>) ll;
                        opp.setLegList(cast);
                    } catch (Exception ignored) {}
                }
                opp = historyService.getRepository().save(opp);
                log.info("Created opportunity from scan data: id={}", opp.getId());
            }

            int lots = body.get("lots") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            String broker = (String) body.getOrDefault("broker", "PAPER");
            List<Map<String, Object>> legs = opp.getLegList();
            boolean isMultiLeg = legs != null && !legs.isEmpty() && !"BID_PARITY".equals(opp.getStrategyType());

            if (broker != null && !broker.isBlank() && !"PAPER".equalsIgnoreCase(broker)) {
                // Real broker selected -- place an actual order via the same engine auto-exec
                // uses, dispatching on legList presence for the correct leg shape.
                opp.setUserId(userId);
                Map<String, Object> liveResult = executionRouter.executeTradeForUser(opp, lots, broker, userId);
                resp.putAll(liveResult);
                addAuditLog("MANUAL_LIVE_TRADE", resp.get("status") != null ? resp.get("status").toString() : "ERROR",
                    "Manual LIVE trade via " + broker + " for " + opp.getUnderlying() + " " + opp.getStrike()
                    + ": " + resp.get("message"));
                return ResponseEntity.ok(resp);
            }

            // PAPER: simulate a fill using live quotes and record it directly (no broker call).
            opp.setStatus("RUNNING");
            historyService.getRepository().save(opp);

            try {
                // No cap on paper trades -- Max Open Positions is a real risk control meant for
                // LIVE capital exposure (still enforced there, user-configurable in Auto-Trade
                // settings); paper trading has no real money or margin at stake, so restricting
                // how many a user can explore at once serves no purpose and was just getting in
                // the way of testing strategies.
                int lotSize = getLotSize(opp.getUnderlying());

                if (isMultiLeg) {
                    List<String> symbols = new ArrayList<>();
                    List<Map<String, Object>> resolvedLegs = new ArrayList<>();
                    for (Map<String, Object> leg : legs) {
                        int strike = ((Number) leg.get("strike")).intValue();
                        String optionType = (String) leg.get("optionType");
                        String sym = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), strike, optionType);
                        Map<String, Object> resolved = new LinkedHashMap<>(leg);
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
                        .userId(1L)
                        .broker("PAPER")
                        .opportunityId(opp.getId())
                        .underlying(opp.getUnderlying())
                        .strike(opp.getStrike())
                        .action(opp.getAction())
                        .strategyType(opp.getStrategyType())
                        .lots(lots)
                        .lotSize(lotSize)
                        .targetEdge(opp.getEdgeAfterCosts())
                        .entryCost(BigDecimal.valueOf(entryCost))
                        .status("OPEN")
                        .enteredAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .build();
                    livePos.setLegs(resolvedLegs);
                    livePositionRepo.save(livePos);
                    resp.put("livePositionId", livePos.getId());

                    addAuditLog("PAPER_TRADE", "SUCCESS",
                        "Entered " + opp.getUnderlying() + " " + opp.getAction() + " (" + resolvedLegs.size() + "-leg, PAPER)");
                    resp.put("status", "SUCCESS");
                    resp.put("opportunityId", opp.getId());
                    resp.put("underlying", opp.getUnderlying());
                    resp.put("strike", opp.getStrike());
                    resp.put("action", opp.getAction());
                    resp.put("tradeStatus", "RUNNING");
                    resp.put("message", opp.getUnderlying() + " " + opp.getAction() + " entered as PAPER trade (" + resolvedLegs.size() + " legs)");
                    return ResponseEntity.ok(resp);
                }

                // Legacy single-strike CE+PE+FUT shape (Bid Parity / normal parity)
                double ceLive = 0, peLive = 0;
                if (opp.getExpiryDate() != null && opp.getStrike() != null) {
                    String ceSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "CE");
                    String peSymbol = optionChainService.buildNfoSymbol(opp.getUnderlying(), opp.getExpiryDate(), opp.getStrike(), "PE");
                    Map<String, OptionChainService.OptionQuote> quotes = optionChainService.fetchQuotes(List.of(ceSymbol, peSymbol));
                    if (quotes.containsKey(ceSymbol) && quotes.get(ceSymbol).lastPrice > 0) ceLive = quotes.get(ceSymbol).lastPrice;
                    if (quotes.containsKey(peSymbol) && quotes.get(peSymbol).lastPrice > 0) peLive = quotes.get(peSymbol).lastPrice;
                }
                if (ceLive > 0) opp.setCeEntryPrice(BigDecimal.valueOf(ceLive));
                if (peLive > 0) opp.setPeEntryPrice(BigDecimal.valueOf(peLive));
                historyService.getRepository().save(opp);

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
                    .broker("PAPER")
                    .opportunityId(opp.getId())
                    .underlying(opp.getUnderlying())
                    .strike(opp.getStrike())
                    .action(opp.getAction())
                    .strategyType(opp.getStrategyType())
                    .lots(lots)
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
                log.warn("Failed to create live position: {}", e.getMessage());
                resp.put("status", "ERROR");
                resp.put("message", "Failed to record paper trade: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("Trade execution failed: {}", e.getMessage(), e);
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

    @GetMapping("/funds")
    public ResponseEntity<Map<String, Object>> getBrokerFunds(@RequestParam String broker) {
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            List<com.stokr.broker.BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
            if (accounts.isEmpty()) {
                resp.put("error", "No active account found for broker " + broker);
                return ResponseEntity.ok(resp);
            }
            com.stokr.broker.BrokerAccount account = accounts.get(0);
            com.stokr.broker.BrokerAdapter adapter = brokerService.getAdapter(broker);
            java.math.BigDecimal margin = adapter.getAvailableMargin(account.getAccessToken());
            resp.put("availableCash", margin != null ? margin.doubleValue() : 0.0);
            resp.put("broker", broker);
        } catch (Exception e) {
            resp.put("error", e.getMessage());
        }
        return ResponseEntity.ok(resp);
    }
    @GetMapping("/top-picks")
    public ResponseEntity<Map<String, Object>> scanTopPicks(
            @RequestParam(defaultValue = "ALL") String underlying,
            @RequestParam(defaultValue = "1000.0") double minEdge) {
            
        String cacheKey = underlying + "_" + minEdge;
        SwrCacheEntry cached = topPicksCache.get(cacheKey);
        long now = System.currentTimeMillis();
        
        if (cached != null) {
            if (now - cached.timestamp > 15000) {
                synchronized(cached) {
                    if (!cached.isUpdating) {
                        cached.isUpdating = true;
                        java.util.concurrent.CompletableFuture.runAsync(() -> {
                            try {
                                ResponseEntity<Map<String, Object>> fresh = doScanTopPicks(underlying, minEdge);
                                topPicksCache.put(cacheKey, new SwrCacheEntry(System.currentTimeMillis(), fresh));
                            } catch (Exception e) {
                                log.error("Error updating top picks cache: ", e);
                            } finally {
                                SwrCacheEntry latest = topPicksCache.get(cacheKey);
                                if (latest != null) latest.isUpdating = false;
                            }
                        });
                    }
                }
            }
            return cached.response;
        }
        
        ResponseEntity<Map<String, Object>> fresh = doScanTopPicks(underlying, minEdge);
        topPicksCache.put(cacheKey, new SwrCacheEntry(now, fresh));
        return fresh;
    }

    private ResponseEntity<Map<String, Object>> doScanTopPicks(String underlying, double minEdge) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean marketClosed = nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30));
        
        List<Map<String, Object>> allOpps = new ArrayList<>();
        
        if (!marketClosed) {
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f1 = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return bidParityService.scanBidParity(underlying); } catch (Exception e) { return new ArrayList<>(); }
            });
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f2 = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return boxSpreadService.scanBoxSpread(underlying); } catch (Exception e) { return new ArrayList<>(); }
            });
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f3 = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return verticalSpreadService.scanVerticalSpread(underlying); } catch (Exception e) { return new ArrayList<>(); }
            });
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f4 = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return butterflySpreadService.scanButterflySpread(underlying); } catch (Exception e) { return new ArrayList<>(); }
            });
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f5 = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return condorSpreadService.scanCondorSpread(underlying); } catch (Exception e) { return new ArrayList<>(); }
            });
            java.util.concurrent.CompletableFuture<List<Map<String, Object>>> f6 = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                List<Map<String, Object>> ironCondors = new ArrayList<>();
                List<String> targets = "ALL".equalsIgnoreCase(underlying) ? List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY") : List.of(underlying);
                for (String u : targets) {
                    try {
                        List<Map<String, Object>> res = scanIronCondorForUnderlying(u);
                        if (res != null) ironCondors.addAll(res);
                    } catch (Exception e) { e.printStackTrace(); }
                }
                return ironCondors;
            });

            try {
                java.util.concurrent.CompletableFuture.allOf(f1, f2, f3, f4, f5, f6).join();
                if (f1.get() != null) allOpps.addAll(f1.get());
                if (f2.get() != null) allOpps.addAll(f2.get());
                if (f3.get() != null) allOpps.addAll(f3.get());
                if (f4.get() != null) allOpps.addAll(f4.get());
                if (f5.get() != null) allOpps.addAll(f5.get());
                if (f6.get() != null) allOpps.addAll(f6.get());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            try {
                var result = historyService.getHistoryByDate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
                if (result != null) {
                    allOpps.addAll(result.stream()
                        .filter(opp -> "ALL".equalsIgnoreCase(underlying) || underlying.equalsIgnoreCase(opp.getUnderlying()))
                        .map(OptionArbOpportunity::toMap)
                        .toList());
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
        
        System.out.println("allOpps size: " + allOpps.size());
        java.time.LocalDateTime nowTime = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        String scanTimeStr = nowTime.toString();
        for (Map<String, Object> opp : allOpps) {
            if (!opp.containsKey("scanTime") && !opp.containsKey("entryTime")) {
                opp.put("scanTime", scanTimeStr);
            }
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> opp : allOpps) {
            if (opp.get("edgeAfterCosts") instanceof Number) {
                double edge = ((Number) opp.get("edgeAfterCosts")).doubleValue();
                if (edge >= minEdge) {
                    filtered.add(opp);
                }
            }
        }
        
        filtered.sort((a, b) -> {
            double edgeA = ((Number) a.get("edgeAfterCosts")).doubleValue();
            double edgeB = ((Number) b.get("edgeAfterCosts")).doubleValue();
            return Double.compare(edgeB, edgeA);
        });
        
        if (filtered.size() > 25) {
            filtered = filtered.subList(0, 25);
        }
        
        markExistingPositions(filtered);
        System.out.println("filtered size: " + filtered.size());
        
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("timestamp", System.currentTimeMillis());
        resp.put("underlying", underlying);
        resp.put("marketClosed", marketClosed);
        resp.put("opportunities", filtered);
        resp.put("count", filtered.size());
        
        if (marketClosed) {
            try {
                var testResult = historyService.getHistoryByDate(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
                resp.put("debug_result_size", testResult != null ? testResult.size() : -1);
                resp.put("debug_allOpps_size", allOpps.size());
            } catch(Exception e) { resp.put("debug_error", e.getMessage()); }
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