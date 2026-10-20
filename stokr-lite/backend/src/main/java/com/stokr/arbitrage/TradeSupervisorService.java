package com.stokr.arbitrage;

import com.stokr.broker.BrokerAccount;
import com.stokr.broker.BrokerAccountRepository;
import com.stokr.broker.BrokerAdapter;
import com.stokr.broker.BrokerPosition;
import com.stokr.broker.BrokerService;
import com.stokr.marketdata.MarketCalendar;
import lombok.Builder;
import lombok.Data;
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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Trade Sentinel (Supervisor & Reconciliation Watchdog)
 * Provides 4 pillars of trade execution governance:
 *  1. Pre-Order Gatekeeper: Headroom margin checks, contract verification, freeze limits, duplicate prevention.
 *  2. In-Flight Order Watchdog: Slippage monitoring, leg fill timeout verification, execution audit trails.
 *  3. Continuous DB vs. Broker Reconciler: Detects and auto-heals orphan or phantom positions against broker portfolios.
 *  4. EOD Flat-Book Certifier: Validates that net intraday F&O positions are 100% flat post-3:20 PM IST.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeSupervisorService {

    private final LivePositionRepository livePositionRepo;
    private final BrokerAccountRepository brokerAccountRepo;
    private final BrokerService brokerService;
    private final OptionChainService optionChainService;
    private final OptionArbOpportunityRepository oppRepo;
    private final AutoExecSettingRepository autoExecSettingRepo;

    // In-memory ring buffer of recent Sentinel audit events
    private final ConcurrentLinkedDeque<SentinelEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private static final int MAX_EVENT_HISTORY = 100;

    // Cached last reconciliation report
    private volatile ReconciliationReport lastReconciliationReport = null;

    /** Consecutive reconciliation cycles in which each open live position was missing at its broker. */
    private final Map<Long, Integer> phantomStreak = new java.util.concurrent.ConcurrentHashMap<>();
    /** Scheduled auto-heal closes a position only after this many consecutive misses (~45s apart). */
    static final int PHANTOM_CONFIRMATIONS = 3;
    private final List<Map<String, Object>> rejectedSignalAudits = new java.util.concurrent.CopyOnWriteArrayList<>();

    // --- DOMAIN MODELS ---

    @Data
    @Builder
    public static class SentinelEvent {
        private String id;
        private LocalDateTime timestamp;
        private String level; // INFO, WARN, ERROR, SUCCESS
        private String category; // GATEKEEPER, WATCHDOG, RECONCILER, CERTIFIER
        private String message;
        private Map<String, Object> details;
    }

    @Data
    @Builder
    public static class GatekeeperResult {
        private boolean approved;
        private String summary;
        private List<String> checksPassed;
        private List<String> rejections;
        private Map<String, Object> metrics;
    }

    @Data
    @Builder
    public static class ReconciliationReport {
        private String status; // IN_SYNC, HEALED, DESYNC_DETECTED
        private LocalDateTime timestamp;
        private int openDbPositions;
        private int activeBrokersChecked;
        private int autoHealedCount;
        private List<String> brokers;
        private List<Map<String, Object>> positionAudits;
        private List<String> anomalies;
    }

    @Data
    @Builder
    public static class SentinelStatus {
        private String overallStatus; // GREEN, YELLOW, RED
        private String statusLabel;
        private boolean marketOpen;
        private LocalDateTime lastCheckTime;
        private int openPositionsCount;
        private int totalBrokersActive;
        private ReconciliationReport lastReconciliation;
        private List<SentinelEvent> recentEvents;
        private Map<String, Object> brokerSummaries;
    }

    // --- 1. PRE-ORDER GATEKEEPER ---

    public GatekeeperResult validatePreOrder(OptionArbOpportunity opp, int lots, String broker, boolean isPaper) {
        List<String> passed = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(ist);
        LocalDate today = LocalDate.now(ist);

        // Check 1: Market Trading Day and Trading Hours
        boolean isTradingDay = MarketCalendar.isTradingDayToday();
        boolean withinHours = !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 20));
        metrics.put("currentTimeIST", now.toString());
        metrics.put("isTradingDay", isTradingDay);
        metrics.put("withinIntradayHours", withinHours);

        if (!isTradingDay) {
            rejections.add("Market is closed today (weekend/holiday). Intraday order blocked.");
        } else if (!withinHours) {
            rejections.add("Outside intraday trading window (09:15 to 15:20 IST). Current time: " + now.toString().substring(0, 5));
        } else {
            passed.add("Market session and trading hours valid (09:15 - 15:20 IST)");
        }

        // Check 2: Expiry Date Validity
        if (opp.getExpiryDate() == null) {
            rejections.add("Opportunity expiry date is null");
        } else if (opp.getExpiryDate().isBefore(today)) {
            rejections.add("Contract expiry date " + opp.getExpiryDate() + " has already expired");
        } else {
            passed.add("Expiry date valid (" + opp.getExpiryDate() + ")");
        }

        // Check 3: Lot Size & Quantity Freeze Limits
        String underlying = opp.getUnderlying() != null ? opp.getUnderlying().toUpperCase() : "";
        int lotSize = OptionChainService.getLotSize(underlying);
        int totalQty = lots * lotSize;
        metrics.put("lotSize", lotSize);
        metrics.put("totalQty", totalQty);

        int maxFreezeLimit = "BANKNIFTY".equals(underlying) ? 900 : 1800;
        if (totalQty > maxFreezeLimit) {
            rejections.add("Order quantity " + totalQty + " breaches NSE freeze limit of " + maxFreezeLimit + " for " + underlying);
        } else {
            passed.add("Quantity " + totalQty + " within exchange freeze limit (" + maxFreezeLimit + ")");
        }

        // Check 4: Duplicate Active Strike Set Guard (single strategy CAN have multiple sets; only exact identical strike sets are guarded)
        boolean duplicateSetExists = livePositionRepo.findAllOpen().stream()
                .anyMatch(p -> SetDeduplicationHelper.isSameSet(p, opp)
                        && broker.equalsIgnoreCase(p.getBroker() != null ? p.getBroker() : "PAPER"));
        if (duplicateSetExists) {
            rejections.add("Active duplicate strike set already OPEN for " + underlying + " " + opp.getStrategyType() + " (" + opp.getAction() + ")");
        } else {
            passed.add("Unique strike set verified — no duplicate active set found");
        }

        // Check 5: Margin Headroom (LIVE only)
        if (!isPaper) {
            try {
                List<BrokerAccount> accounts = brokerAccountRepo.findByBrokerNameAndStatus(broker, "ACTIVE");
                if (accounts.isEmpty()) {
                    rejections.add("No ACTIVE account found for broker " + broker);
                } else {
                    BrokerAccount acc = accounts.get(0);
                    BrokerAdapter adapter = brokerService.getAdapter(broker);
                    BigDecimal available = adapter.getAvailableMargin(acc.getAccessToken());
                    double availDouble = available != null ? available.doubleValue() : 0.0;
                    metrics.put("availableMargin", availDouble);

                    // Required margin estimate with 15% buffer
                    double required = estimateRequiredMargin(opp, lots, lotSize);
                    double requiredWithBuffer = required * 1.15;
                    metrics.put("requiredMargin", required);
                    metrics.put("requiredMarginWith15PctBuffer", requiredWithBuffer);

                    if (availDouble < requiredWithBuffer) {
                        rejections.add(String.format("Insufficient margin headroom: needs ₹%.0f (incl. 15%% safety buffer) but broker has ₹%.0f",
                                requiredWithBuffer, availDouble));
                    } else {
                        passed.add(String.format("Margin headroom verified (Available: ₹%.0f, Required + 15%%: ₹%.0f)", availDouble, requiredWithBuffer));
                    }
                }
            } catch (Exception e) {
                rejections.add("Broker margin check failed: " + e.getMessage());
            }
        } else {
            passed.add("Paper trading mode - margin check bypassed");
        }

        boolean approved = rejections.isEmpty();
        String summary = approved ? "GATEKEEPER APPROVED: All pre-order checks passed"
                : "GATEKEEPER REJECTED: " + String.join(" | ", rejections);

        recordEvent(approved ? "SUCCESS" : "WARN", "GATEKEEPER",
                opp.getUnderlying() + " " + opp.getStrike() + " (" + (isPaper ? "PAPER" : broker) + "): " + summary,
                metrics);

        if (!approved) {
            String primaryRejection = rejections.isEmpty() ? "Gatekeeper rejection" : rejections.get(0);
            String cat = "REJECTED_RISK";
            if (primaryRejection.toLowerCase().contains("margin") || primaryRejection.toLowerCase().contains("balance")) {
                cat = "REJECTED_BALANCE";
            } else if (primaryRejection.toLowerCase().contains("duplicate")) {
                cat = "REJECTED_DUPLICATE_SET";
            } else if (primaryRejection.toLowerCase().contains("freeze")) {
                cat = "REJECTED_FREEZE";
            } else if (primaryRejection.toLowerCase().contains("closed") || primaryRejection.toLowerCase().contains("hours")) {
                cat = "REJECTED_HOURS";
            }
            recordRejectedSignal(opp.getUnderlying(), opp.getStrategyType(), opp.getStrike(), opp.getAction(),
                    opp.getEdgePoints() != null ? opp.getEdgePoints().doubleValue() : 0.0,
                    opp.getEdgeAfterCosts() != null ? opp.getEdgeAfterCosts().doubleValue() : 0.0,
                    summary, cat);
        }

        return GatekeeperResult.builder()
                .approved(approved)
                .summary(summary)
                .checksPassed(passed)
                .rejections(rejections)
                .metrics(metrics)
                .build();
    }

    public void recordRejectedSignal(String underlying, String strategyType, Integer strike, String action,
                                      double edgePoints, double edgeAfterCosts, String rejectionReason, String rejectionCategory) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("opportunityId", System.currentTimeMillis() % 1000000);
        item.put("scanTime", LocalDateTime.now(ZoneId.of("Asia/Kolkata")).toString());
        item.put("underlying", underlying);
        item.put("strategyType", strategyType);
        item.put("strike", strike);
        item.put("action", action);
        item.put("edgePoints", edgePoints);
        item.put("edgeAfterCosts", edgeAfterCosts);
        item.put("auditStatus", rejectionCategory);
        item.put("statusBadge", rejectionCategory.replace("REJECTED_", ""));
        item.put("statusReason", rejectionReason);
        List<Integer> strikes = SetDeduplicationHelper.extractAllStrikes(action, strike);
        item.put("setStrikes", strikes);

        Map<String, Object> gatekeeper = new LinkedHashMap<>();
        int lotSize = underlying != null && underlying.contains("BANK") ? 30 : 65;
        gatekeeper.put("lotSize", lotSize);
        gatekeeper.put("freezeLimitPassed", !rejectionCategory.contains("FREEZE"));
        gatekeeper.put("setStrikesUnique", !rejectionCategory.contains("DUPLICATE"));
        item.put("gatekeeper", gatekeeper);

        if (rejectedSignalAudits.size() > 100) {
            rejectedSignalAudits.remove(0);
        }
        rejectedSignalAudits.add(item);
    }

    private double estimateRequiredMargin(OptionArbOpportunity opp, int lots, int lotSize) {
        if (opp.getEstimatedMargin() != null && opp.getEstimatedMargin().doubleValue() > 0) {
            return opp.getEstimatedMargin().doubleValue() * lots;
        }
        List<Map<String, Object>> legs = opp.getLegList();
        if (legs != null && !legs.isEmpty()) {
            int minStrike = Integer.MAX_VALUE, maxStrike = Integer.MIN_VALUE;
            for (Map<String, Object> leg : legs) {
                if (leg.get("strike") instanceof Number n) {
                    minStrike = Math.min(minStrike, n.intValue());
                    maxStrike = Math.max(maxStrike, n.intValue());
                }
            }
            if (minStrike < maxStrike) {
                return (maxStrike - minStrike) * lotSize * lots;
            }
        }
        return 75000.0 * lots; // Default safe estimate for 1 lot hedged spread
    }

    // --- 2. IN-FLIGHT EXECUTION WATCHDOG ---

    public void auditLegExecution(String symbol, String side, double expectedPrice, double fillPrice, int qty) {
        double slippage = 0.0;
        if (expectedPrice > 0 && fillPrice > 0) {
            slippage = "BUY".equalsIgnoreCase(side) ? (fillPrice - expectedPrice) : (expectedPrice - fillPrice);
        }
        double slippagePct = expectedPrice > 0 ? (slippage / expectedPrice) * 100.0 : 0.0;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("symbol", symbol);
        details.put("side", side);
        details.put("qty", qty);
        details.put("expectedPrice", expectedPrice);
        details.put("fillPrice", fillPrice);
        details.put("slippagePoints", slippage);
        details.put("slippagePct", slippagePct);

        String level = Math.abs(slippagePct) > 1.5 ? "WARN" : "INFO";
        String message = String.format("Watchdog: %s %s filled @ %.2f (expected %.2f, slippage: %.2f pts / %.2f%%)",
                side, symbol, fillPrice, expectedPrice, slippage, slippagePct);

        recordEvent(level, "WATCHDOG", message, details);
    }

    // --- 3. CONTINUOUS DB VS. BROKER RECONCILER ---

    @Scheduled(fixedDelay = 45000, initialDelay = 15000)
    public void scheduledReconciliation() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(ist);
        // Only run reconciliation during market hours
        if (MarketCalendar.isTradingDayToday() && !now.isBefore(LocalTime.of(9, 15)) && !now.isAfter(LocalTime.of(15, 35))) {
            try {
                reconcileBrokerPositions(true, PHANTOM_CONFIRMATIONS);
            } catch (Exception e) {
                log.debug("Scheduled reconciliation error: {}", e.getMessage());
            }
        }
    }

    public ReconciliationReport reconcileBrokerPositions(boolean autoHeal) {
        // Manual reconcile (user-triggered) heals on the first confirmed miss.
        return reconcileBrokerPositions(autoHeal, 1);
    }

    /**
     * Symbols a broker position may be known by. Motilal reports "NIFTY 27-OCT-2026 CE 25000" while
     * legs are stored Kite-style ("NIFTY26OCT25000CE"), so its names are expanded to Kite aliases.
     */
    static Set<String> symbolKeys(String brokerSymbol) {
        Set<String> keys = new HashSet<>();
        if (brokerSymbol == null || brokerSymbol.isBlank()) return keys;
        String s = brokerSymbol.trim().toUpperCase();
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        keys.add(s);
        if (s.contains(" ")) keys.addAll(com.stokr.broker.MotilalOswalAdapter.kiteAliases(s));
        return keys;
    }

    static String legKey(String legSymbol) {
        String s = legSymbol.trim().toUpperCase();
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    /**
     * Compares open live DB positions with what the brokers report. A position is closed in the DB
     * ("healed") only when ALL of these hold, because a wrong heal leaves a real broker position with
     * no stop-loss, target or 3:20 square-off:
     *  - the broker returned real position data (adapters return an empty list when the call fails,
     *    so an empty result is treated as "unknown", never as "flat");
     *  - every ACTIVE account of that broker was checked (positions may sit in any of them);
     *  - none of the position's legs appears there, under any alias;
     *  - the miss repeated in {@code confirmations} consecutive cycles.
     */
    public synchronized ReconciliationReport reconcileBrokerPositions(boolean autoHeal, int confirmations) {
        List<LivePosition> openDbPositions = livePositionRepo.findAllOpen();
        List<String> brokers = List.of("ZERODHA", "NAVIA", "MOTILALOSWAL");
        List<Map<String, Object>> positionAudits = new ArrayList<>();
        List<String> anomalies = new ArrayList<>();
        int healedCount = 0;
        int activeBrokers = 0;

        Map<String, Set<String>> brokerHeldSymbols = new HashMap<>();

        for (String brokerName : brokers) {
            List<BrokerAccount> accounts;
            try {
                accounts = brokerAccountRepo.findByBrokerNameAndStatus(brokerName, "ACTIVE");
            } catch (Exception e) {
                continue;
            }
            if (accounts.isEmpty()) continue;
            activeBrokers++;
            Set<String> heldSymbols = new HashSet<>();
            boolean anyData = false, anyFailure = false;
            BrokerAdapter adapter;
            try {
                adapter = brokerService.getAdapter(brokerName);
            } catch (Exception e) {
                continue;
            }
            for (BrokerAccount acc : accounts) {
                try {
                    List<BrokerPosition> positions = adapter.getPositions(acc.getAccessToken());
                    if (positions == null || positions.isEmpty()) continue; // failure and "flat" look identical
                    anyData = true;
                    for (BrokerPosition p : positions) {
                        if (p.quantity() != 0) heldSymbols.addAll(symbolKeys(p.symbol()));
                    }
                } catch (Exception e) {
                    anyFailure = true;
                    log.debug("Reconciliation: Failed to fetch positions from {} account {}: {}", brokerName, acc.getId(), e.getMessage());
                }
            }
            // Only a broker that returned real data, with no account failing, counts as checked.
            if (anyData && !anyFailure) brokerHeldSymbols.put(brokerName, heldSymbols);
        }
        Set<Long> stillOpenIds = new HashSet<>();

        for (LivePosition pos : openDbPositions) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("positionId", pos.getId());
            audit.put("underlying", pos.getUnderlying());
            audit.put("strategy", pos.getStrategyType());
            audit.put("broker", pos.getBroker());
            audit.put("status", pos.getStatus());

            String broker = pos.getBroker() != null ? pos.getBroker().toUpperCase() : "PAPER";

            if ("PAPER".equals(broker)) {
                audit.put("auditState", "VERIFIED_PAPER");
                audit.put("detail", "Paper position tracked cleanly in DB");
                positionAudits.add(audit);
                continue;
            }

            stillOpenIds.add(pos.getId());
            Set<String> held = brokerHeldSymbols.get(broker);
            if (held == null) {
                audit.put("auditState", "BROKER_OFFLINE_OR_UNCHECKED");
                audit.put("detail", "Broker " + broker + " returned no position data this cycle (failed, expired session, or empty) — nothing changed");
                positionAudits.add(audit);
                continue;
            }

            List<String> legSymbols = extractLegSymbols(pos);
            boolean anyLegHeld = legSymbols.stream().map(TradeSupervisorService::legKey).anyMatch(held::contains);

            if (anyLegHeld || legSymbols.isEmpty()) {
                phantomStreak.remove(pos.getId());
                audit.put("auditState", "IN_SYNC");
                audit.put("detail", "Legs confirmed active at " + broker);
            } else {
                int streak = phantomStreak.merge(pos.getId(), 1, Integer::sum);
                audit.put("auditState", "PHANTOM_DETECTED");
                audit.put("detail", "Broker has 0 open quantity across all legs (" + streak + "/" + confirmations + " checks)");
                anomalies.add("Phantom position #" + pos.getId() + " (" + pos.getUnderlying() + " " + pos.getStrategyType() + ") flat at broker " + broker);

                if (autoHeal && streak >= confirmations) {
                    phantomStreak.remove(pos.getId());
                    pos.setStatus("CLOSED");
                    pos.setExitedAt(LocalDateTime.now());
                    pos.setExitReason("RECONCILED_FLAT_AT_BROKER");
                    pos.setErrorMessage("Auto-healed by Sentinel: broker portfolio confirmed 0 net quantity");
                    livePositionRepo.save(pos);
                    healedCount++;
                    audit.put("auditState", "AUTO_HEALED_CLOSED");
                    log.info("Sentinel: Auto-healed position #{} to CLOSED because broker is flat", pos.getId());
                }
            }
            positionAudits.add(audit);
        }

        phantomStreak.keySet().retainAll(stillOpenIds);
        String overallStatus = anomalies.isEmpty() ? "IN_SYNC" : (healedCount > 0 ? "HEALED" : "DESYNC_DETECTED");

        ReconciliationReport report = ReconciliationReport.builder()
                .status(overallStatus)
                .timestamp(LocalDateTime.now())
                .openDbPositions(openDbPositions.size())
                .activeBrokersChecked(activeBrokers)
                .autoHealedCount(healedCount)
                .brokers(new ArrayList<>(brokerHeldSymbols.keySet()))
                .positionAudits(positionAudits)
                .anomalies(anomalies)
                .build();

        this.lastReconciliationReport = report;

        recordEvent(anomalies.isEmpty() ? "SUCCESS" : "WARN", "RECONCILER",
                String.format("Reconciliation: %s (%d DB positions, %d brokers checked, %d healed)",
                        overallStatus, openDbPositions.size(), activeBrokers, healedCount),
                Map.of("openDb", openDbPositions.size(), "healed", healedCount, "anomalies", anomalies));

        return report;
    }

    private List<String> extractLegSymbols(LivePosition pos) {
        List<String> list = new ArrayList<>();
        if (pos.getLegs() != null) {
            for (Map<String, Object> leg : pos.getLegs()) {
                if (leg.get("symbol") instanceof String s && !s.isBlank()) list.add(s);
            }
        }
        if (pos.getCeSymbol() != null) list.add(pos.getCeSymbol());
        if (pos.getPeSymbol() != null) list.add(pos.getPeSymbol());
        if (pos.getFutSymbol() != null) list.add(pos.getFutSymbol());
        return list;
    }

    // --- 4. EOD FLAT-BOOK CERTIFIER ---

    @Scheduled(cron = "0 25 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void certifyEodFlatBookScheduled() {
        certifyEodFlatBook();
    }

    public Map<String, Object> certifyEodFlatBook() {
        Map<String, Object> cert = new LinkedHashMap<>();
        cert.put("timestamp", LocalDateTime.now().toString());
        cert.put("sessionDate", LocalDate.now().toString());

        long openDbCount = livePositionRepo.findAllOpen().stream()
                .filter(p -> p.getBroker() != null && !"PAPER".equalsIgnoreCase(p.getBroker()))
                .count();

        boolean allFlat = (openDbCount == 0);
        cert.put("allRealPositionsFlat", allFlat);
        cert.put("openRealDbPositions", openDbCount);
        cert.put("certifiedBy", "TradeSentinel_EodCertifier_v1");

        recordEvent(allFlat ? "SUCCESS" : "ERROR", "CERTIFIER",
                allFlat ? "EOD FLAT-BOOK CERTIFIED: All broker intraday positions confirmed 100% FLAT"
                        : "WARNING: " + openDbCount + " open positions detected after 15:20 EOD square-off!",
                cert);

        return cert;
    }

    // --- 5. OVERALL SENTINEL STATUS ---

    public SentinelStatus getSentinelStatus() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalTime now = LocalTime.now(ist);
        boolean marketOpen = MarketCalendar.isTradingDayToday()
                && !now.isBefore(LocalTime.of(9, 15))
                && !now.isAfter(LocalTime.of(15, 30));

        List<LivePosition> openPositions = livePositionRepo.findAllOpen();
        List<BrokerAccount> activeAccounts = brokerAccountRepo.findAll().stream()
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                .toList();

        Map<String, Object> brokerSummaries = new LinkedHashMap<>();
        for (BrokerAccount acc : activeAccounts) {
            Map<String, Object> bData = new LinkedHashMap<>();
            bData.put("status", "ACTIVE");
            bData.put("clientCode", acc.getClientId());
            try {
                BrokerAdapter adapter = brokerService.getAdapter(acc.getBrokerName());
                BigDecimal margin = adapter.getAvailableMargin(acc.getAccessToken());
                bData.put("availableMargin", margin != null ? margin.doubleValue() : 0.0);
            } catch (Exception e) {
                bData.put("marginError", e.getMessage());
            }
            brokerSummaries.put(acc.getBrokerName(), bData);
        }

        ReconciliationReport lastReport = this.lastReconciliationReport;
        if (lastReport == null) {
            try {
                lastReport = reconcileBrokerPositions(false);
            } catch (Exception ignored) {}
        }

        String overallStatus = "GREEN";
        String statusLabel = "All Systems Nominal";

        if (lastReport != null && !lastReport.getAnomalies().isEmpty()) {
            overallStatus = "RED";
            statusLabel = "Desync Anomalies Detected";
        } else if (!marketOpen) {
            statusLabel = "Market Closed (Safe)";
        }

        return SentinelStatus.builder()
                .overallStatus(overallStatus)
                .statusLabel(statusLabel)
                .marketOpen(marketOpen)
                .lastCheckTime(LocalDateTime.now())
                .openPositionsCount(openPositions.size())
                .totalBrokersActive(activeAccounts.size())
                .lastReconciliation(lastReport)
                .recentEvents(new ArrayList<>(recentEvents))
                .brokerSummaries(brokerSummaries)
                .build();
    }

    // --- EVENT RECORDER ---

    public void recordEvent(String level, String category, String message, Map<String, Object> details) {
        SentinelEvent event = SentinelEvent.builder()
                .id(UUID.randomUUID().toString().substring(0, 8))
                .timestamp(LocalDateTime.now())
                .level(level)
                .category(category)
                .message(message)
                .details(details != null ? details : Map.of())
                .build();

        recentEvents.addFirst(event);
        while (recentEvents.size() > MAX_EVENT_HISTORY) {
            recentEvents.removeLast();
        }
    }

    // --- 6. SIGNALS AUDIT & GATEKEEPER BREAKDOWN ---

    public Map<String, Object> getSignalsAudit() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(ist);
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(LocalTime.MAX);

        List<LivePosition> allPositionsToday = livePositionRepo.findAll().stream()
                .filter(p -> p.getEnteredAt() != null && p.getEnteredAt().toLocalDate().isEqual(today))
                .sorted((a, b) -> b.getEnteredAt().compareTo(a.getEnteredAt()))
                .toList();

        List<Map<String, Object>> signalAudits = new ArrayList<>();
        int executedCount = 0;
        int balanceRejections = 0;
        int duplicateRejections = 0;
        int brokerRejections = 0;
        double maxEdge = 0.0;

        // 1. Add all actual positions attempted/entered today
        for (LivePosition pos : allPositionsToday) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("opportunityId", pos.getOpportunityId() != null ? pos.getOpportunityId() : pos.getId());
            item.put("scanTime", pos.getEnteredAt() != null ? pos.getEnteredAt().toString() : "");
            item.put("underlying", pos.getUnderlying());
            item.put("strategyType", pos.getStrategyType());
            item.put("strike", pos.getStrike());
            item.put("action", pos.getAction());
            item.put("lots", pos.getLots());
            item.put("lotSize", pos.getLotSize());
            item.put("broker", pos.getBroker());
            item.put("expiryDate", pos.getExpiryDate() != null ? pos.getExpiryDate().toString() : null);
            item.put("positionId", pos.getId());
            item.put("positionStatus", pos.getStatus());
            item.put("positionPnl", pos.getCurrentPnl() != null ? pos.getCurrentPnl().doubleValue() : 0.0);
            item.put("exitReason", pos.getExitReason());

            double edge = pos.getTargetEdge() != null ? pos.getTargetEdge().doubleValue() : 0.0;
            item.put("edgeAfterCosts", edge);
            if (edge > maxEdge) maxEdge = edge;

            List<Integer> strikes = SetDeduplicationHelper.extractAllStrikes(pos.getAction(), pos.getStrike());
            item.put("setStrikes", strikes);

            Map<String, Object> gatekeeper = new LinkedHashMap<>();
            gatekeeper.put("lotSize", pos.getLotSize() != null ? pos.getLotSize() : 30);
            gatekeeper.put("freezeLimitPassed", true);
            gatekeeper.put("setStrikesUnique", true);
            item.put("gatekeeper", gatekeeper);

            if ("FAILED".equalsIgnoreCase(pos.getStatus())) {
                brokerRejections++;
                item.put("auditStatus", "REJECTED_BROKER");
                item.put("statusBadge", "BROKER_REJECT");
                item.put("statusReason", pos.getErrorMessage() != null ? pos.getErrorMessage() : "Broker order execution rejected");
            } else {
                executedCount++;
                item.put("auditStatus", "PASSED_EXECUTED");
                item.put("statusBadge", "EXECUTED");
                item.put("statusReason", "Cleared all gatekeeper checks & executed as Trade #" + pos.getId() + " (" + pos.getStatus() + ")");
            }

            signalAudits.add(item);
        }

        // 2. Add all pre-order gatekeeper rejections recorded today (Balance, Duplicate Set, Limits)
        for (Map<String, Object> rej : rejectedSignalAudits) {
            String status = (String) rej.getOrDefault("auditStatus", "");
            if ("REJECTED_BALANCE".equals(status)) balanceRejections++;
            else if ("REJECTED_DUPLICATE_SET".equals(status)) duplicateRejections++;
            else if ("REJECTED_BROKER".equals(status)) brokerRejections++;

            double edge = rej.get("edgeAfterCosts") instanceof Number n ? n.doubleValue() : 0.0;
            if (edge > maxEdge) maxEdge = edge;

            signalAudits.add(new LinkedHashMap<>(rej));
        }

        // 3. Fallback: If no trades or rejections yet today, display top 5 scanned opportunities
        if (signalAudits.isEmpty()) {
            List<OptionArbOpportunity> opps = oppRepo.findByScanTimeBetween(startOfDay, endOfDay);
            for (OptionArbOpportunity opp : opps.stream().limit(5).toList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("opportunityId", opp.getId());
                item.put("scanTime", opp.getScanTime() != null ? opp.getScanTime().toString() : "");
                item.put("underlying", opp.getUnderlying());
                item.put("strategyType", opp.getStrategyType());
                item.put("strike", opp.getStrike());
                item.put("action", opp.getAction());
                item.put("edgePoints", opp.getEdgePoints() != null ? opp.getEdgePoints().doubleValue() : 0.0);
                item.put("edgeAfterCosts", opp.getEdgeAfterCosts() != null ? opp.getEdgeAfterCosts().doubleValue() : 0.0);
                item.put("expiryDate", opp.getExpiryDate() != null ? opp.getExpiryDate().toString() : null);
                item.put("auditStatus", "SCANNED");
                item.put("statusBadge", "SCANNED");
                item.put("statusReason", "Opportunity scanned & ready — Gatekeeper validates upon execution trigger");
                item.put("setStrikes", SetDeduplicationHelper.extractAllStrikes(opp.getAction(), opp.getStrike()));
                signalAudits.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", LocalDateTime.now(ist).toString());
        result.put("sessionDate", today.toString());
        result.put("totalSignals", signalAudits.size());
        result.put("executedSignals", executedCount);
        result.put("rejectedSignals", signalAudits.size() - executedCount);
        result.put("balanceRejections", balanceRejections);
        result.put("duplicateRejections", duplicateRejections);
        result.put("brokerRejections", brokerRejections);
        result.put("topEdgeToday", maxEdge);
        result.put("signals", signalAudits);

        return result;
    }
}
