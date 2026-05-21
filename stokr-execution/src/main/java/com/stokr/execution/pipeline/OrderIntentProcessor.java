package com.stokr.execution.pipeline;

import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.common.telemetry.SignalDistributionTelemetryService;
import com.stokr.execution.comparison.ExecutionComparisonService;
import com.stokr.execution.risk.RiskContextFactory;
import com.stokr.execution.sizing.PositionSizingService;
import com.stokr.execution.service.ExecutionService;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.trace.ExecutionTraceService;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.common.notification.NotificationEvent;
import com.stokr.common.notification.NotificationPublisher;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import com.stokr.risk.service.RiskEngineService;
import com.stokr.risk.service.RiskEvaluationTraceService;
import com.stokr.risk.service.RiskEventRecorder;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIntentProcessor {

    private final StrategySignalRepository signalRepository;
    private final OrderLifecycleService orderLifecycleService;
    private final RiskEngineService riskEngineService;
    private final RiskEvaluationTraceService riskEvaluationTraceService;
    private final RiskEventRecorder riskEventRecorder;
    private final ExecutionService executionService;
    private final LiveTradingTraderEligibilityService liveTradingTraderEligibilityService;
    private final ObjectProvider<NotificationPublisher> notificationPublisher;
    private final ExecutionTraceService executionTraceService;
    private final RiskContextFactory riskContextFactory;
    private final SignalDistributionTelemetryService signalDistributionTelemetryService;
    private final PositionSizingService positionSizingService;
    private final ExecutionComparisonService executionComparisonService;

    @Value("${stokr.risk.zone:Asia/Kolkata}")
    private String riskZone;

    @Value("${stokr.strategy.default-quantity:1}")
    private BigDecimal defaultQuantity;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private UUID systemUserId;

    /**
     * Creates OMS order from persisted signal, runs risk, emits execution events, then routes to execution (sync or async).
     */
    @Transactional
    public void processSignalIntent(SignalPersistedMessage msg, boolean synchronousExecution) {
        StrategySignalEntity signal = signalRepository.findById(msg.signalId())
                .orElseThrow(() -> new IllegalStateException("Signal not found: " + msg.signalId()));

        if (signal.getSignalType() == SignalType.HOLD) {
            log.debug("signal.hold.skip signalId={}", signal.getId());
            return;
        }

        String idempotencyKey = "signal:" + signal.getId();
        ExecutionMode mode = parseMode(msg.executionMode());
        String strategyKey =
                signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY;
        UUID userId = resolveUserId(signal);
        boolean isSystemUser = systemUserId.equals(userId);

        // System-generated signals bypass paper/SIM eligibility gate (no trader account needed).
        // LIVE mode always requires the full gate regardless of origin.
        if (mode == ExecutionMode.LIVE) {
            LiveTraderEligibilityResult gate =
                    liveTradingTraderEligibilityService.evaluateForLiveOrder(userId, strategyKey, "ZERODHA");
            if (!gate.allowed()) {
                log.warn(
                        "live.order.blocked.pre_signal signalId={} reason={}",
                        signal.getId(),
                        gate.reasonCode()
                );
                riskEventRecorder.record(userId, null, gate.reasonCode(), "REJECT", gate.message());
                if (!isSystemUser) notifyEligibility(userId, gate);
                signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "LIVE_GATE");
                return;
            }
        } else if (!isSystemUser) {
            LiveTraderEligibilityResult paper = liveTradingTraderEligibilityService.evaluateForPaperTrading(strategyKey);
            if (!paper.allowed()) {
                log.warn(
                        "paper.order.blocked.pre_signal signalId={} reason={}",
                        signal.getId(),
                        paper.reasonCode()
                );
                riskEventRecorder.record(userId, null, paper.reasonCode(), "REJECT", paper.message());
                notifyEligibility(userId, paper);
                signalDistributionTelemetryService.recordGateRejected(userId, signal.getId(), "PAPER_GATE");
                return;
            }
        }

        if (mode == ExecutionMode.BOTH) {
            dispatchBothMode(signal, userId, idempotencyKey, strategyKey, synchronousExecution);
            return;
        }

        OmsOrder draft = buildDraftFromSignal(signal, mode, userId);
        OmsOrder order = orderLifecycleService.createOrGetIdempotent(userId, idempotencyKey, draft);
        if (order.getState() != OrderState.CREATED) {
            log.info("order.idempotent.hit orderId={} state={}", order.getId(), order.getState());
            signalDistributionTelemetryService.recordIdempotentHit(userId);
            return;
        }

        signalDistributionTelemetryService.recordOrderCreatedFromSignal(userId, order.getId(), signal.getId());
        executionTraceService.trace(order, ExecutionEventType.SIGNAL_GENERATED, Map.of(
                "signalId", signal.getId().toString(),
                "symbol", signal.getSymbol() != null ? signal.getSymbol() : ""
        ));

        executionTraceService.trace(order, ExecutionEventType.ORDER_REQUESTED, Map.of(
                "symbol", signal.getSymbol() != null ? signal.getSymbol() : "",
                "side", mapSide(signal),
                "executionMode", mode.name()
        ));

        order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        Instant evalInstant = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();
        BigDecimal atrRatio = atrToCloseRatio(signal);
        RiskContext ctx = riskContextFactory.build(userId, order, zone, evalInstant, atrRatio);

        RiskDecision decision = riskEngineService.evaluate(ctx);
        riskEvaluationTraceService.record(ctx, decision, order.getId());
        if (!decision.allowed()) {
            riskEventRecorder.record(
                    userId,
                    order.getId(),
                    decision.reasonCode() != null ? decision.reasonCode() : "RISK",
                    "REJECT",
                    decision.message()
            );
            order = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
            executionTraceService.trace(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                    "phase", "RISK",
                    "reason", decision.message() != null ? decision.message() : ""
            ));
            signalDistributionTelemetryService.recordRiskRejected(userId, signal.getId());
            return;
        }

        executionTraceService.trace(order, ExecutionEventType.RISK_CHECK_PASSED, Map.of(
                "riskReason", decision.message() != null ? decision.message() : "OK"
        ));

        order = orderLifecycleService.transition(order.getId(), OrderState.PENDING_SUBMISSION, null);

        long fillKey = fillDeterminismKey(signal);
        Instant anchor = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : order.getCreatedAt();

        executionService.dispatch(
                new ExecutionDispatchMessage(
                        order.getId(),
                        order.getUserId(),
                        signal.getId(),
                        order.getBrokerVendor(),
                        0,
                        signal.getBacktestRunId(),
                        msg.executionMode(),
                        fillKey,
                        anchor
                ),
                synchronousExecution
        );
        executionTraceService.trace(order, ExecutionEventType.EXECUTION_DISPATCHED, Map.of(
                "channel", synchronousExecution ? "SYNC" : "RABBIT_EXECUTION",
                "attempt", 0
        ));
    }

    private static long fillDeterminismKey(StrategySignalEntity signal) {
        long k = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp().toEpochMilli() : 0L;
        k ^= signal.getId().getMostSignificantBits();
        k ^= signal.getId().getLeastSignificantBits();
        if (signal.getUserId() != null) {
            k ^= signal.getUserId().getMostSignificantBits();
        }
        return k;
    }

    private OmsOrder buildDraftFromSignal(StrategySignalEntity signal, ExecutionMode mode, UUID userId) {
        OmsOrder o = new OmsOrder();
        o.setSignalId(signal.getId());
        o.setStrategyKey(signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY);
        o.setExecutionMode(mode);
        o.setSymbol(signal.getSymbol());
        o.setSide(mapSide(signal));
        o.setOrderType("MARKET");
        o.setQuantity(positionSizingService.resolveQuantity(
                signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY,
                userId,
                signal.getSuggestedQty(),
                signal.getEntryReferencePrice()));
        o.setLimitPrice(null);
        o.setStopPrice(signal.getStopPrice());
        o.setTargetPrice(signal.getTargetPrice());
        o.setEntryReferencePrice(signal.getEntryReferencePrice());
        o.setBacktestRunId(signal.getBacktestRunId());
        o.setBrokerVendor(mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM");
        return o;
    }

    private static BigDecimal atrToCloseRatio(StrategySignalEntity signal) {
        if (signal.getAtrValue() == null || signal.getEntryReferencePrice() == null) {
            return null;
        }
        if (signal.getEntryReferencePrice().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return signal.getAtrValue().divide(signal.getEntryReferencePrice(), 8, RoundingMode.HALF_UP);
    }

    private static String mapSide(StrategySignalEntity signal) {
        return switch (signal.getSignalType()) {
            case BUY -> "BUY";
            case SELL -> "SELL";
            case EXIT -> "SELL";
            case HOLD -> "HOLD";
        };
    }

    private static ExecutionMode parseMode(String executionMode) {
        if (executionMode == null) {
            return ExecutionMode.SIMULATED;
        }
        String m = executionMode.trim().toUpperCase();
        if ("LIVE".equals(m)) {
            return ExecutionMode.LIVE;
        }
        if ("PAPER".equals(m)) {
            return ExecutionMode.PAPER;
        }
        if ("BOTH".equals(m)) {
            return ExecutionMode.BOTH;
        }
        return ExecutionMode.SIMULATED;
    }

    private UUID resolveUserId(StrategySignalEntity signal) {
        return signal.getUserId() != null ? signal.getUserId() : systemUserId;
    }

    private void notifyEligibility(UUID userId, LiveTraderEligibilityResult gate) {
        notificationPublisher.ifAvailable(pub -> pub.publish(new NotificationEvent(
                "IN_APP",
                "TRADER_ELIGIBILITY_BLOCK",
                userId,
                new LinkedHashMap<>(Map.of(
                        "reasonCode", gate.reasonCode() != null ? gate.reasonCode() : "",
                        "message", gate.message() != null ? gate.message() : ""
                ))
        )));
    }

    private void dispatchBothMode(StrategySignalEntity signal, UUID userId, String baseIdempotencyKey,
                                   String strategyKey, boolean synchronousExecution) {
        // PAPER leg — no live gate needed
        OmsOrder paperDraft = buildDraftFromSignal(signal, ExecutionMode.PAPER, userId);
        OmsOrder paperOrder = orderLifecycleService.createOrGetIdempotent(
                userId, baseIdempotencyKey + ":PAPER", paperDraft);

        // LIVE leg — run through live gate
        LiveTraderEligibilityResult gate = liveTradingTraderEligibilityService
                .evaluateForLiveOrder(userId, strategyKey, "ZERODHA");
        OmsOrder liveOrder = null;
        if (gate.allowed()) {
            OmsOrder liveDraft = buildDraftFromSignal(signal, ExecutionMode.LIVE, userId);
            liveOrder = orderLifecycleService.createOrGetIdempotent(
                    userId, baseIdempotencyKey + ":LIVE", liveDraft);
        } else {
            log.warn("both_mode.live_leg.blocked signalId={} reason={}", signal.getId(), gate.reasonCode());
            riskEventRecorder.record(userId, null, gate.reasonCode(), "REJECT", gate.message());
        }

        // Link pairs and record comparison stub
        if (liveOrder != null) {
            paperOrder.setPairedOrderId(liveOrder.getId());
            liveOrder.setPairedOrderId(paperOrder.getId());
            executionComparisonService.recordPairDispatched(
                    signal.getId(), liveOrder.getId(), paperOrder.getId(), strategyKey, signal.getSymbol());
        }

        long fillKey = fillDeterminismKey(signal);
        Instant anchor = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();

        // Transition and dispatch PAPER
        if (paperOrder.getState() == OrderState.CREATED) {
            paperOrder = orderLifecycleService.transition(paperOrder.getId(), OrderState.VALIDATED, null);
            paperOrder = orderLifecycleService.transition(paperOrder.getId(), OrderState.PENDING_SUBMISSION, null);
            executionService.dispatch(new ExecutionDispatchMessage(
                    paperOrder.getId(), userId, signal.getId(), "SIM", 0,
                    signal.getBacktestRunId(), "PAPER", fillKey, anchor), synchronousExecution);
        }

        // Transition and dispatch LIVE
        if (liveOrder != null && liveOrder.getState() == OrderState.CREATED) {
            liveOrder = orderLifecycleService.transition(liveOrder.getId(), OrderState.VALIDATED, null);
            liveOrder = orderLifecycleService.transition(liveOrder.getId(), OrderState.PENDING_SUBMISSION, null);
            executionService.dispatch(new ExecutionDispatchMessage(
                    liveOrder.getId(), userId, signal.getId(), "ZERODHA", 0,
                    signal.getBacktestRunId(), "LIVE", fillKey, anchor), synchronousExecution);
        }

        log.info("both_mode.dispatched signalId={} paper={} live={}",
                signal.getId(), paperOrder.getId(), liveOrder != null ? liveOrder.getId() : "BLOCKED");
    }
}
