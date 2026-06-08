package com.stokr.execution.pipeline;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.execution.service.OrderPlacementService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Places broker exit orders when signal outcome tracker detects SL/target/breakeven hits.
 * Exit legs use a separate idempotency key and no signal_id (ux_oms_orders_user_signal constraint).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalOutcomeExitService {

    private static final Set<String> EXIT_OUTCOMES = Set.of(
            "TARGET_HIT", "STOPLOSS_HIT", "SL_HIT", "BREAKEVEN_EXIT",
            "PRESSURE_EXIT", "LIQUIDITY_PROTECTION", "FEED_PROTECTION", "TIME_EXIT"
    );

    private static final Set<OrderState> FILLED_ENTRY_STATES = Set.of(
            OrderState.FILLED,
            OrderState.PARTIALLY_FILLED,
            OrderState.ACCEPTED
    );

    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final OrderPlacementService orderPlacementService;
    private final BrokerPositionTruthService brokerPositionTruthService;

    @Value("${stokr.strategy.exit.auto-exit-enabled:true}")
    private boolean autoExitEnabled;

    @Value("${stokr.strategy.exit.auto-exit-on-breakeven:true}")
    private boolean autoExitOnBreakeven;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSignalOutcome(OperationalRealtimeEvent event) {
        if (!autoExitEnabled || event == null || !"signal_outcome".equals(event.topic())) {
            return;
        }
        Map<String, Object> payload = event.payload();
        if (payload == null) {
            return;
        }
        String outcomeStatus = stringVal(payload.get("outcomeStatus"));
        if (outcomeStatus == null || !EXIT_OUTCOMES.contains(outcomeStatus)) {
            return;
        }
        if ("BREAKEVEN_EXIT".equals(outcomeStatus) && !autoExitOnBreakeven) {
            return;
        }
        UUID signalId = parseUuid(payload.get("signalId"));
        if (signalId == null) {
            return;
        }
        dispatchForSignal(signalId, outcomeStatus);
    }

    /**
     * One-off / admin repair: place missing exit OMS legs for signals that already have terminal outcomes.
     */
    public Map<String, Object> backfillMissingOutcomeExits(Instant since, int maxSignals) {
        List<StrategySignalEntity> signals = signalRepository.findTerminalOutcomesSince(
                since,
                EXIT_OUTCOMES,
                List.of(SignalProvenance.REPLAY, SignalProvenance.LAB));
        int examined = 0;
        int dispatched = 0;
        int skipped = 0;
        int failed = 0;
        List<String> dispatchedIds = new ArrayList<>();
        for (StrategySignalEntity signal : signals) {
            if (examined >= maxSignals) {
                break;
            }
            examined++;
            String prefix = "outcome-exit:" + signal.getId() + ":";
            if (omsOrderRepository.existsByDeletedFalseAndIdempotencyKeyStartingWith(prefix)) {
                skipped++;
                continue;
            }
            try {
                dispatchForSignal(signal.getId(), signal.getOutcomeStatus());
                dispatched++;
                dispatchedIds.add(signal.getId().toString());
            } catch (Exception ex) {
                failed++;
                log.warn("signal.outcome_exit.backfill_failed signalId={} err={}",
                        signal.getId(), ex.getMessage());
            }
        }
        log.info("signal.outcome_exit.backfill examined={} dispatched={} skipped={} failed={} since={}",
                examined, dispatched, skipped, failed, since);
        return Map.of(
                "examined", examined,
                "dispatched", dispatched,
                "skipped", skipped,
                "failed", failed,
                "since", since.toString(),
                "signalIds", dispatchedIds
        );
    }

    @Transactional
    public void dispatchForSignal(UUID signalId, String outcomeStatus) {
        StrategySignalEntity signal = signalRepository.findById(signalId).orElse(null);
        if (signal == null || signal.isDeleted()) {
            return;
        }
        if (Boolean.TRUE.equals(signal.getTestTrade())) {
            return;
        }
        SignalProvenance source = signal.getSignalSource();
        if (source == SignalProvenance.REPLAY || source == SignalProvenance.LAB) {
            return;
        }

        List<OmsOrder> entryOrders = resolveEntryOrders(signalId);
        if (entryOrders.isEmpty()) {
            log.info("signal.outcome_exit.no_entry_orders signalId={} outcome={}", signalId, outcomeStatus);
            return;
        }

        log.info("signal.outcome_exit.dispatch signalId={} outcome={} entryLegs={}",
                signalId, outcomeStatus, entryOrders.size());

        for (OmsOrder entry : entryOrders) {
            if (entry.getUserId() == null || entry.getSide() == null) {
                continue;
            }
            if (!FILLED_ENTRY_STATES.contains(entry.getState())) {
                continue;
            }
            if ("HOLD".equalsIgnoreCase(entry.getSide())) {
                continue;
            }
            try {
                placeExitForEntry(entry, signal, outcomeStatus);
            } catch (Exception ex) {
                log.warn("signal.outcome_exit.failed signalId={} orderId={} outcome={} err={}",
                        signalId, entry.getId(), outcomeStatus, ex.getMessage());
            }
        }
    }

    /**
     * BOTH-mode LIVE legs omit signal_id (ux_oms_orders_user_signal); include paired LIVE entries.
     */
    private List<OmsOrder> resolveEntryOrders(UUID signalId) {
        List<OmsOrder> direct = omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId);
        Map<UUID, OmsOrder> merged = new LinkedHashMap<>();
        for (OmsOrder order : direct) {
            if (order.getId() != null) {
                merged.put(order.getId(), order);
            }
            UUID pairedId = order.getPairedOrderId();
            if (pairedId != null) {
                omsOrderRepository.findById(pairedId)
                        .filter(o -> !o.isDeleted())
                        .ifPresent(paired -> merged.putIfAbsent(paired.getId(), paired));
            }
        }
        return List.copyOf(merged.values());
    }

    private void placeExitForEntry(OmsOrder entry, StrategySignalEntity signal, String outcomeStatus) {
        UUID userId = entry.getUserId();
        String symbol = entry.getSymbol();
        ExitResolution exit = resolveExit(userId, symbol, entry);
        if (exit == null) {
            log.debug("signal.outcome_exit.skip_flat userId={} symbol={} signalId={}",
                    userId, symbol, signal.getId());
            return;
        }

        String strategyKey = entry.getStrategyKey() != null ? entry.getStrategyKey() : signal.getStrategyName();
        String idempotencyKey = "outcome-exit:" + signal.getId() + ":" + entry.getId() + ":" + outcomeStatus;

        OmsOrder placed = orderPlacementService.place(userId, new CreateOrderRequest(
                symbol,
                exit.side(),
                "MARKET",
                exit.qty(),
                null,
                exit.mode(),
                exit.broker(),
                strategyKey,
                idempotencyKey,
                null,
                signal.getCandleTimestamp(),
                signal.getEntryReferencePrice(),
                null,
                true,
                "EXIT_SAFE",
                false,
                null
        ));

        log.info("signal.outcome_exit.placed signalId={} outcome={} userId={} symbol={} side={} qty={} mode={} orderId={} state={}",
                signal.getId(), outcomeStatus, userId, symbol, exit.side(), exit.qty().toPlainString(), exit.mode(),
                placed.getId(), placed.getState());
    }

    /**
     * When Zerodha still holds qty, always place a LIVE exit — PAPER-filled entry legs are not sufficient.
     */
    private ExitResolution resolveExit(UUID userId, String symbol, OmsOrder entry) {
        brokerPositionTruthService.syncUser(userId);
        BrokerPositionTruthSnapshot snap = brokerPositionTruthService.snapshot(userId);
        String norm = BrokerPositionTruthService.normalizeSymbol(symbol);
        for (BrokerPositionTruthSnapshot.BrokerTruthPositionRow row : snap.positions()) {
            if (norm.equals(row.symbol()) && row.brokerQty() != null && row.brokerQty().signum() != 0) {
                BigDecimal brokerQty = row.brokerQty();
                String side = brokerQty.signum() > 0 ? "SELL" : "BUY";
                return new ExitResolution(brokerQty.abs(), side, ExecutionMode.LIVE, "ZERODHA");
            }
        }
        if (entry.getQuantity() != null && entry.getQuantity().signum() > 0) {
            String side = "BUY".equalsIgnoreCase(entry.getSide()) ? "SELL" : "BUY";
            ExecutionMode mode = entry.getExecutionMode() != null ? entry.getExecutionMode() : ExecutionMode.PAPER;
            String broker = mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM";
            return new ExitResolution(entry.getQuantity(), side, mode, broker);
        }
        return null;
    }

    private record ExitResolution(BigDecimal qty, String side, ExecutionMode mode, String broker) {
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static UUID parseUuid(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
