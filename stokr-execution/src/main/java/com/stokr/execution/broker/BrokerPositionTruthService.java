package com.stokr.execution.broker;

import com.stokr.broker.model.BrokerPositionDetail;
import com.stokr.common.events.ExecutionAlertEvent;
import com.stokr.common.events.realtime.RealtimeBridgeEvents;
import com.stokr.execution.guard.ExecutionGuardMode;
import com.stokr.execution.guard.ExecutionGuardSeverity;
import com.stokr.execution.guard.ExecutionGuardViolation;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.oms.reconciliation.BrokerReconciliationService;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.service.ExecutionLedgerService;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.SignalManualExitSuppressionService;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import com.stokr.user.repository.BrokerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Broker is source of truth for open quantity. Reconciles against OMS LIVE ledger,
 * blocks counter-trades, and halts strategy runtime when positions close externally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerPositionTruthService {

    private static final String EXTERNAL_EXIT_STRATEGY = "EXTERNAL_BROKER_EXIT";
    private static final String EXTERNAL_EXIT_LINKAGE = "EXTERNAL_BROKER_EXIT";
    private static final String EXTERNAL_EXIT_EXECUTION_KIND = "EXTERNAL_EXIT";

    private static final Collection<OrderState> ACTIVE_ORDER_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private static final Set<String> PENDING_KITE_STATUSES = Set.of(
            "OPEN", "VALIDATION PENDING", "PUT ORDER REQ RECEIVED", "TRIGGER PENDING",
            "OPEN PENDING", "MODIFY PENDING", "MODIFY VALIDATION PENDING", "CANCEL PENDING",
            "AMO REQ RECEIVED"
    );

    private final ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;
    private final OmsExecutionRepository omsExecutionRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final ExecutionLedgerService executionLedgerService;
    private final PortfolioAccountingService portfolioAccountingService;
    private final ReconciliationEventRepository reconciliationEventRepository;
    private final StrategyInstanceRepository strategyInstanceRepository;
    private final StrategySignalRepository strategySignalRepository;
    private final BrokerReconciliationService brokerReconciliationService;
    private final ApplicationEventPublisher eventPublisher;
    private final BrokerAccountRepository brokerAccountRepository;
    private final SignalManualExitSuppressionService manualExitSuppressionService;

    private final ConcurrentHashMap<UUID, BrokerPositionTruthSnapshot> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> pendingExternalBrokerExits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> brokerClosedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastPublishedFingerprint = new ConcurrentHashMap<>();

    @Value("${stokr.broker-truth.stale-ms:15000}")
    private long staleMs;

    @Value("${stokr.broker-truth.block-exit-minutes:30}")
    private long blockExitMinutes;

    @Value("${stokr.broker-truth.external-exit-confirm-seconds:60}")
    private long externalExitConfirmSeconds;

    public BrokerPositionTruthSnapshot snapshot(UUID userId) {
        BrokerPositionTruthSnapshot snap = cache.get(userId);
        if (snap == null) {
            var broker = zerodhaBrokerOperationsService.status(userId);
            return BrokerPositionTruthSnapshot.empty(broker.connected());
        }
        if (snap.lastSyncAt() != null && Duration.between(snap.lastSyncAt(), Instant.now()).toMillis() > staleMs) {
            return new BrokerPositionTruthSnapshot(
                    BrokerPositionTruthSyncState.STALE,
                    snap.lastSyncAt(),
                    snap.syncLatencyMs(),
                    snap.brokerConnected(),
                    snap.positions(),
                    snap.mismatches(),
                    snap.brokerClosedSymbols(),
                    snap.blockedSymbols(),
                    snap.pendingBrokerOrders(),
                    "Broker truth stale — polling recovery active"
            );
        }
        return snap;
    }

    @Transactional
    public BrokerPositionTruthSnapshot syncUser(UUID userId) {
        long start = System.nanoTime();
        var brokerStatus = zerodhaBrokerOperationsService.status(userId);
        if (!brokerStatus.connected() || !brokerStatus.tokenValid()) {
            BrokerPositionTruthSnapshot empty = BrokerPositionTruthSnapshot.empty(false);
            cache.put(userId, empty);
            return empty;
        }

        List<BrokerPositionDetail> brokerDetails;
        try {
            brokerDetails = zerodhaBrokerOperationsService.fetchBrokerPositionDetails(userId);
        } catch (Exception ex) {
            log.warn("broker.truth.fetch_failed user={} {}", userId, ex.getMessage());
            BrokerPositionTruthSnapshot stale = snapshot(userId);
            cache.put(userId, stale);
            return stale;
        }

        Map<String, BigDecimal> brokerQty = new LinkedHashMap<>();
        Map<String, BrokerPositionDetail> brokerMeta = new LinkedHashMap<>();
        for (BrokerPositionDetail d : brokerDetails) {
            String key = normalizeSymbol(d.symbolKey());
            brokerQty.merge(key, d.quantity(), BigDecimal::add);
            brokerMeta.put(key, d);
        }

        Map<String, BigDecimal> internalQty = omsExecutionRepository.computeLiveNetQtyBySymbol(userId).stream()
                .collect(Collectors.toMap(
                        row -> normalizeSymbol(String.valueOf(row[0])),
                        row -> (BigDecimal) row[1],
                        BigDecimal::add,
                        LinkedHashMap::new
                ));

        List<BrokerPositionTruthSnapshot.BrokerTruthMismatch> mismatches = new ArrayList<>();
        Set<String> brokerClosed = new LinkedHashSet<>();
        Set<String> blocked = new LinkedHashSet<>();

        for (Map.Entry<String, BigDecimal> internalEntry : internalQty.entrySet()) {
            String symbol = internalEntry.getKey();
            BigDecimal iQty = internalEntry.getValue();
            if (iQty == null || iQty.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal bQty = brokerQty.getOrDefault(symbol, BigDecimal.ZERO);
            if (bQty.compareTo(BigDecimal.ZERO) == 0) {
                mismatches.add(new BrokerPositionTruthSnapshot.BrokerTruthMismatch(
                        symbol, "GHOST_INTERNAL_POSITION", bQty, iQty, Instant.now()));
                blocked.add(symbol);
                if (shouldConfirmExternalBrokerExit(userId, symbol, Instant.now())) {
                    handleExternalBrokerExit(userId, symbol, iQty);
                    brokerClosed.add(symbol);
                    if (isWithinExitBlockWindow(userId, symbol, Instant.now())) {
                        blocked.add(symbol);
                    }
                } else {
                    log.warn("broker.truth.external_exit_pending user={} symbol={} internalQty={}",
                            userId, symbol, iQty);
                }
            } else if (bQty.compareTo(iQty) != 0) {
                mismatches.add(new BrokerPositionTruthSnapshot.BrokerTruthMismatch(
                        symbol, "QUANTITY_MISMATCH", bQty, iQty, Instant.now()));
                blocked.add(symbol);
            } else if (oppositeSign(bQty, iQty)) {
                mismatches.add(new BrokerPositionTruthSnapshot.BrokerTruthMismatch(
                        symbol, "BROKER_CONFLICT", bQty, iQty, Instant.now()));
                blocked.add(symbol);
            }
            if (bQty.compareTo(BigDecimal.ZERO) != 0) {
                clearExternalBrokerExitConfirmation(userId, symbol);
            }
        }

        for (Map.Entry<String, BigDecimal> brokerEntry : brokerQty.entrySet()) {
            if (!internalQty.containsKey(brokerEntry.getKey())
                    && brokerEntry.getValue().compareTo(BigDecimal.ZERO) != 0) {
                mismatches.add(new BrokerPositionTruthSnapshot.BrokerTruthMismatch(
                        brokerEntry.getKey(), "ORPHAN_BROKER_POSITION",
                        brokerEntry.getValue(), BigDecimal.ZERO, Instant.now()));
            }
        }

        List<BrokerPositionTruthSnapshot.BrokerTruthPositionRow> rows = new ArrayList<>();
        Set<String> allSymbols = new LinkedHashSet<>();
        allSymbols.addAll(brokerQty.keySet());
        allSymbols.addAll(internalQty.keySet());
        for (String symbol : allSymbols) {
            BigDecimal bq = brokerQty.getOrDefault(symbol, BigDecimal.ZERO);
            BigDecimal iq = internalQty.getOrDefault(symbol, BigDecimal.ZERO);
            BrokerPositionDetail meta = brokerMeta.get(symbol);
            String rowState = rowSyncState(bq, iq, blocked.contains(symbol));
            rows.add(new BrokerPositionTruthSnapshot.BrokerTruthPositionRow(
                    symbol,
                    bq,
                    iq,
                    meta != null ? meta.averagePrice() : null,
                    meta != null ? meta.realisedPnl() : null,
                    meta != null ? meta.unrealisedPnl() : null,
                    meta != null ? meta.product() : null,
                    rowState
            ));
        }

        int pendingOrders = countPendingBrokerOrders(userId);
        BrokerPositionTruthSyncState syncState = resolveSyncState(mismatches, pendingOrders);
        long latencyMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);

        BrokerPositionTruthSnapshot snap = new BrokerPositionTruthSnapshot(
                syncState,
                Instant.now(),
                latencyMs,
                true,
                List.copyOf(rows),
                List.copyOf(mismatches),
                Set.copyOf(brokerClosed),
                Set.copyOf(blocked),
                pendingOrders,
                syncState == BrokerPositionTruthSyncState.VERIFIED
                        ? "Broker and OMS positions aligned"
                        : mismatches.size() + " reconciliation item(s)"
        );
        cache.put(userId, snap);
        if (!mismatches.isEmpty()) {
            brokerReconciliationService.triggerForUser(userId, "ZERODHA");
        }
        publishIfChanged(userId, snap);
        return snap;
    }

    private void publishIfChanged(UUID userId, BrokerPositionTruthSnapshot snap) {
        if (!snap.brokerConnected()) {
            lastPublishedFingerprint.remove(userId);
            return;
        }
        String fingerprint = fingerprint(snap);
        String prev = lastPublishedFingerprint.put(userId, fingerprint);
        if (prev != null && prev.equals(fingerprint)) {
            return;
        }
        int openCount = (int) snap.positions().stream()
                .filter(p -> p.brokerQty() != null && p.brokerQty().compareTo(BigDecimal.ZERO) != 0)
                .count();
        eventPublisher.publishEvent(new RealtimeBridgeEvents.PositionUpdated(
                userId,
                snap.syncState() != null ? snap.syncState().name() : "UNKNOWN",
                openCount,
                snap.lastSyncAt()
        ));
    }

    private static String fingerprint(BrokerPositionTruthSnapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append(snap.syncState()).append('|').append(snap.pendingBrokerOrders()).append('|');
        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : snap.positions()) {
            sb.append(row.symbol()).append(':')
                    .append(row.brokerQty()).append(':')
                    .append(row.brokerAvgPrice()).append(':')
                    .append(row.brokerRealizedPnl()).append(':')
                    .append(row.brokerUnrealizedPnl()).append(';');
        }
        return sb.toString();
    }

    public List<ExecutionGuardViolation> validateForExecution(
            UUID userId,
            String symbol,
            String side,
            ExecutionGuardMode guardMode,
            Instant now
    ) {
        List<ExecutionGuardViolation> out = new ArrayList<>();
        String norm = normalizeSymbol(symbol);
        BrokerPositionTruthSnapshot snap = snapshot(userId);

        if (snap.syncState() == BrokerPositionTruthSyncState.STALE && guardMode == ExecutionGuardMode.ENTRY_STRICT) {
            out.add(new ExecutionGuardViolation(
                    "BROKER_TRUTH_STALE",
                    ExecutionGuardSeverity.CRITICAL,
                    "Broker position truth stale",
                    snap.message()
            ));
        }

        if (snap.blockedSymbols().contains(norm)) {
            if (guardMode == ExecutionGuardMode.EXIT_SAFE && isReducingSide(norm, side, snap)) {
                out.add(new ExecutionGuardViolation(
                        "BROKER_TRUTH_EXIT_REVIEW",
                        ExecutionGuardSeverity.WARNING,
                        "Exit while reconciliation active",
                        "Symbol " + norm + " under broker truth review"
                ));
            } else {
                out.add(new ExecutionGuardViolation(
                        "BROKER_TRUTH_BLOCKED",
                        ExecutionGuardSeverity.CRITICAL,
                        "Execution blocked — broker mismatch",
                        "Symbol " + norm + " blocked until reconciliation"
                ));
            }
        }

        if (snap.brokerClosedSymbols().contains(norm) && isEntrySide(side, snap, norm)) {
            out.add(new ExecutionGuardViolation(
                    "BROKER_EXTERNAL_EXIT",
                    ExecutionGuardSeverity.CRITICAL,
                    "Position closed externally at broker",
                    "No re-entry until broker truth verified"
            ));
        }

        if (hasPendingOppositeOrder(userId, norm, side)) {
            out.add(new ExecutionGuardViolation(
                    "BROKER_PENDING_OPPOSITE",
                    ExecutionGuardSeverity.CRITICAL,
                    "Conflicting broker order pending",
                    "Opposite or duplicate order in flight at broker"
            ));
        }

        BrokerPositionTruthSnapshot.BrokerTruthPositionRow row = snap.positions().stream()
                .filter(p -> norm.equals(p.symbol()))
                .findFirst()
                .orElse(null);
        if (row != null && wouldCauseCounterTrade(row, side)) {
            out.add(new ExecutionGuardViolation(
                    "COUNTER_TRADE_RISK",
                    ExecutionGuardSeverity.CRITICAL,
                    "Counter-trade prevented",
                    "Broker qty " + row.brokerQty() + " conflicts with " + side
            ));
        }

        if (hasPendingExitOrder(userId, norm, snap) && isReducingSide(norm, side, snap)) {
            out.add(new ExecutionGuardViolation(
                    "DUPLICATE_EXIT",
                    ExecutionGuardSeverity.CRITICAL,
                    "Duplicate exit blocked",
                    "Pending exit already exists for " + norm
            ));
        }

        return out;
    }

    public List<UUID> usersNeedingSync() {
        Set<UUID> users = new LinkedHashSet<>();
        omsOrderRepository.findAllLiveActiveOrders(ACTIVE_ORDER_STATES).stream()
                .map(o -> o.getUserId())
                .filter(id -> id != null)
                .forEach(users::add);
        cache.keySet().forEach(users::add);
        brokerAccountRepository.findDistinctUserIdsWithConnectedBroker().forEach(users::add);
        return List.copyOf(users);
    }

    private void handleExternalBrokerExit(UUID userId, String symbol, BigDecimal internalQty) {
        String key = userId + ":" + symbol;
        Instant prev = brokerClosedAt.putIfAbsent(key, Instant.now());
        if (prev != null && Duration.between(prev, Instant.now()).toMinutes() < 2) {
            return;
        }
        log.warn("broker.truth.external_exit user={} symbol={} internalQty={}", userId, symbol, internalQty);
        persistRecon(userId, symbol, "EXTERNAL_BROKER_EXIT", BigDecimal.ZERO, internalQty);
        recordExternalBrokerExitOffset(userId, symbol, internalQty);
        manualExitSuppressionService.suppressAutoExitForSymbol(userId, symbol, "MANUAL: external_broker_exit");
        haltStrategyRuntimeForSymbol(userId, symbol);

        // Auto-update signal outcome for manual broker exit (FIX #2)
        updateSignalOutcomeForManualExit(userId, symbol);

        eventPublisher.publishEvent(new ExecutionAlertEvent(
                "BROKER_EXTERNAL_EXIT",
                null,
                symbol,
                null,
                userId,
                "Position " + symbol + " closed at broker; strategy runtime halted"
        ));
    }

    private void recordExternalBrokerExitOffset(UUID userId, String symbol, BigDecimal internalQty) {
        if (userId == null || internalQty == null || internalQty.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        String normalized = normalizeSymbol(symbol);
        ExternalExitLedgerAnchor anchor = externalExitLedgerAnchor(userId, normalized);
        String idempotencyKey = "external-broker-exit:" + normalized + ":" + anchor.idempotencySuffix();
        if (omsOrderRepository.findByUserIdAndIdempotencyKeyAndDeletedFalse(userId, idempotencyKey).isPresent()) {
            log.info("broker.truth.external_exit_offset_exists user={} symbol={} key={}",
                    userId, normalized, idempotencyKey);
            return;
        }

        BigDecimal qty = internalQty.abs();
        OmsOrder offset = new OmsOrder();
        offset.setUserId(userId);
        offset.setIdempotencyKey(idempotencyKey);
        offset.setStrategyKey(EXTERNAL_EXIT_STRATEGY);
        offset.setExecutionMode(ExecutionMode.LIVE);
        offset.setSymbol(normalized);
        offset.setSide(internalQty.compareTo(BigDecimal.ZERO) > 0 ? "SELL" : "BUY");
        offset.setOrderType("EXTERNAL_EXIT");
        offset.setQuantity(qty);
        offset.setState(OrderState.FILLED);
        offset.setBrokerVendor("ZERODHA");
        offset.setExecutionLinkage(EXTERNAL_EXIT_LINKAGE);
        offset.setExecutionLinkageReason("Broker reported flat; OMS ledger offset recorded after confirmation");

        OmsOrder saved = omsOrderRepository.save(offset);
        executionLedgerService.appendExecution(
                saved,
                null,
                qty,
                anchor.price(),
                EXTERNAL_EXIT_EXECUTION_KIND,
                Instant.now(),
                0L,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                anchor.price(),
                "BROKER_TRUTH",
                null
        );
        portfolioAccountingService.applyFill(userId, normalized, EXTERNAL_EXIT_STRATEGY);
        log.warn("broker.truth.external_exit_offset_recorded user={} symbol={} side={} qty={} price={} orderId={}",
                userId, normalized, saved.getSide(), qty, anchor.price(), saved.getId());
    }

    private ExternalExitLedgerAnchor externalExitLedgerAnchor(UUID userId, String normalizedSymbol) {
        List<OmsExecution> executions = liveExecutionsForSymbol(userId, normalizedSymbol);
        for (int i = executions.size() - 1; i >= 0; i--) {
            OmsExecution execution = executions.get(i);
            if (execution == null || execution.getId() == null) {
                continue;
            }
            BigDecimal price = execution.getAvgPrice();
            if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
                price = BigDecimal.ZERO;
            }
            return new ExternalExitLedgerAnchor(price, execution.getId().toString());
        }
        return new ExternalExitLedgerAnchor(BigDecimal.ZERO, "no-live-execution");
    }

    private List<OmsExecution> liveExecutionsForSymbol(UUID userId, String normalizedSymbol) {
        List<OmsExecution> executions = omsExecutionRepository.findLiveForUserAndSymbolOrdered(userId, normalizedSymbol);
        if (!executions.isEmpty()) {
            return executions;
        }
        String bare = bareSymbol(normalizedSymbol);
        if (!bare.equals(normalizedSymbol)) {
            return omsExecutionRepository.findLiveForUserAndSymbolOrdered(userId, bare);
        }
        return executions;
    }

    private void updateSignalOutcomeForManualExit(UUID userId, String symbol) {
        try {
            // Find all RUNNING signals for this user + symbol created in past 60 minutes and mark them as manually closed
            String normalizedSymbol = normalizeSymbol(symbol);

            // Get all recent signals (created within past 60 minutes) with RUNNING status
            // Use pagination to be safe with large result sets
            Pageable pageRequest = PageRequest.of(0, 1000);
            List<StrategySignalEntity> recentSignals = strategySignalRepository
                    .findRunningSignalsSince(Instant.now().minus(Duration.ofMinutes(60)), pageRequest);

            for (StrategySignalEntity signal : recentSignals) {
                // Check if this signal matches our criteria
                if (signal.getUserId() != null && signal.getUserId().equals(userId)
                        && signal.getSymbol() != null
                        && normalizeSymbol(signal.getSymbol()).equalsIgnoreCase(normalizedSymbol)
                        && "RUNNING".equalsIgnoreCase(signal.getOutcomeStatus())) {

                    signal.setOutcomeStatus("CLOSED");
                    signal.setOutcomeComment("Position manually closed at broker (MANUAL_BROKER_EXIT)");
                    signal.setOutcomeTime(Instant.now());
                    signal.setUpdatedAt(Instant.now());
                    strategySignalRepository.save(signal);
                    log.info("signal.outcome.auto_updated signalId={} reason=MANUAL_BROKER_EXIT symbol={}",
                            signal.getId(), symbol);
                }
            }
        } catch (Exception e) {
            log.warn("signal.outcome.auto_update_failed symbol={} error={}", symbol, e.getMessage());
            // Don't fail the entire manual exit handling if signal update fails
        }
    }

    private void haltStrategyRuntimeForSymbol(UUID userId, String symbol) {
        String norm = normalizeSymbol(symbol);
        String bare = bareSymbol(norm);
        for (StrategyInstance si : strategyInstanceRepository.findAllForUserWithDefinition(userId)) {
            String instSym = si.getSymbol();
            if (instSym == null) {
                continue;
            }
            String instNorm = normalizeSymbol(instSym);
            if (!instNorm.equals(norm) && !bareSymbol(instNorm).equals(bare)) {
                continue;
            }
            if ("RUNNING".equalsIgnoreCase(si.getRuntimeState())) {
                si.setRuntimeState("STOPPED");
                strategyInstanceRepository.save(si);
                log.info("broker.truth.runtime_halted instance={} symbol={}", si.getId(), norm);
            }
        }
    }

    private boolean shouldConfirmExternalBrokerExit(UUID userId, String symbol, Instant now) {
        if (externalExitConfirmSeconds <= 0) {
            return true;
        }
        String key = userId + ":" + normalizeSymbol(symbol);
        Instant firstSeen = pendingExternalBrokerExits.putIfAbsent(key, now);
        if (firstSeen == null) {
            return false;
        }
        if (Duration.between(firstSeen, now).getSeconds() < externalExitConfirmSeconds) {
            return false;
        }
        pendingExternalBrokerExits.remove(key, firstSeen);
        return true;
    }

    private void clearExternalBrokerExitConfirmation(UUID userId, String symbol) {
        String key = userId + ":" + normalizeSymbol(symbol);
        pendingExternalBrokerExits.remove(key);
        brokerClosedAt.remove(key);
    }

    private void persistRecon(UUID userId, String symbol, String kind, BigDecimal brokerQty, BigDecimal internalQty) {
        try {
            var ev = new com.stokr.oms.reconciliation.ReconciliationEvent();
            ev.setUserId(userId);
            ev.setBrokerVendor("ZERODHA");
            ev.setSymbol(symbol);
            ev.setDiscrepancyType(kind);
            ev.setBrokerQty(brokerQty);
            ev.setInternalQty(internalQty);
            ev.setDelta(brokerQty.subtract(internalQty));
            reconciliationEventRepository.save(ev);
        } catch (Exception ex) {
            log.debug("broker.truth.recon_persist_failed {}", ex.getMessage());
        }
    }

    private int countPendingBrokerOrders(UUID userId) {
        try {
            return (int) zerodhaBrokerOperationsService.recentOrders(userId, 200).stream()
                    .filter(o -> o.status() != null && PENDING_KITE_STATUSES.contains(o.status().trim().toUpperCase(Locale.ROOT)))
                    .count();
        } catch (Exception ex) {
            return 0;
        }
    }

    private boolean hasPendingOppositeOrder(UUID userId, String symbol, String side) {
        String bare = bareSymbol(symbol);
        String want = side != null ? side.trim().toUpperCase(Locale.ROOT) : "";
        try {
            return zerodhaBrokerOperationsService.recentOrders(userId, 100).stream()
                    .anyMatch(o -> {
                        if (o.status() == null || !PENDING_KITE_STATUSES.contains(o.status().trim().toUpperCase(Locale.ROOT))) {
                            return false;
                        }
                        String sym = o.symbol() != null ? bareSymbol(normalizeSymbol(o.symbol())) : "";
                        if (!sym.equals(bare) && !sym.equals(symbol)) {
                            return false;
                        }
                        String tx = o.side() != null ? o.side().trim().toUpperCase(Locale.ROOT) : "";
                        return ("BUY".equals(want) && "SELL".equals(tx)) || ("SELL".equals(want) && "BUY".equals(tx));
                    });
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean hasPendingExitOrder(UUID userId, String symbol, BrokerPositionTruthSnapshot snap) {
        return omsOrderRepository.findAllLiveActiveOrders(ACTIVE_ORDER_STATES).stream()
                .anyMatch(o -> userId.equals(o.getUserId())
                        && symbol.equals(normalizeSymbol(o.getSymbol()))
                        && isReducingSide(symbol, o.getSide(), snap));
    }

    private boolean isWithinExitBlockWindow(UUID userId, String symbol, Instant now) {
        if (blockExitMinutes <= 0) {
            return true;
        }
        String key = userId + ":" + normalizeSymbol(symbol);
        Instant closedAt = brokerClosedAt.get(key);
        if (closedAt == null) {
            return true;
        }
        if (Duration.between(closedAt, now).toMinutes() >= blockExitMinutes) {
            brokerClosedAt.remove(key);
            return false;
        }
        return true;
    }

    private static boolean isEntrySide(String side, BrokerPositionTruthSnapshot snap, String symbol) {
        if (!"BUY".equalsIgnoreCase(side != null ? side.trim() : "")) {
            return false;
        }
        return snap.brokerClosedSymbols().contains(symbol);
    }

    private static boolean isReducingSide(String symbol, String side, BrokerPositionTruthSnapshot snap) {
        return snap.positions().stream()
                .filter(p -> symbol.equals(p.symbol()))
                .findFirst()
                .map(p -> {
                    if (p.internalQty().compareTo(BigDecimal.ZERO) > 0) {
                        return "SELL".equalsIgnoreCase(side);
                    }
                    if (p.internalQty().compareTo(BigDecimal.ZERO) < 0) {
                        return "BUY".equalsIgnoreCase(side);
                    }
                    return false;
                })
                .orElse(false);
    }

    private static boolean wouldCauseCounterTrade(
            BrokerPositionTruthSnapshot.BrokerTruthPositionRow row,
            String side
    ) {
        if (side == null || row.internalQty() == null || row.internalQty().compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        BigDecimal bq = row.brokerQty() != null ? row.brokerQty() : BigDecimal.ZERO;
        boolean internalLong = row.internalQty().compareTo(BigDecimal.ZERO) > 0;
        boolean buy = "BUY".equalsIgnoreCase(side.trim());
        if (internalLong && buy && bq.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        return !internalLong && !buy && bq.compareTo(BigDecimal.ZERO) >= 0;
    }

    private static boolean oppositeSign(BigDecimal a, BigDecimal b) {
        return a.signum() != 0 && b.signum() != 0 && a.signum() != b.signum();
    }

    private static BrokerPositionTruthSyncState resolveSyncState(
            List<BrokerPositionTruthSnapshot.BrokerTruthMismatch> mismatches,
            int pendingOrders
    ) {
        if (mismatches.stream().anyMatch(m -> "BROKER_CONFLICT".equals(m.kind()))) {
            return BrokerPositionTruthSyncState.BROKER_CONFLICT;
        }
        if (!mismatches.isEmpty()) {
            return BrokerPositionTruthSyncState.MISMATCH;
        }
        if (pendingOrders > 0) {
            return BrokerPositionTruthSyncState.RECONCILING;
        }
        return BrokerPositionTruthSyncState.VERIFIED;
    }

    private static String rowSyncState(BigDecimal brokerQty, BigDecimal internalQty, boolean blocked) {
        if (blocked) {
            return BrokerPositionTruthSyncState.MISMATCH.name();
        }
        if (brokerQty.compareTo(internalQty) == 0) {
            return BrokerPositionTruthSyncState.VERIFIED.name();
        }
        if (brokerQty.compareTo(BigDecimal.ZERO) == 0 && internalQty.compareTo(BigDecimal.ZERO) != 0) {
            return BrokerPositionTruthSyncState.STALE.name();
        }
        return BrokerPositionTruthSyncState.PENDING_SYNC.name();
    }

    public static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        String t = symbol.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return t;
        }
        if (!t.contains(":") && t.matches("[A-Z0-9._-]+")) {
            return "NSE:" + t;
        }
        return t;
    }

    static String bareSymbol(String symbol) {
        int idx = symbol.indexOf(':');
        return idx >= 0 ? symbol.substring(idx + 1) : symbol;
    }

    private record ExternalExitLedgerAnchor(BigDecimal price, String idempotencySuffix) {
    }
}
