package com.stokr.execution.pipeline;

import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.common.pipeline.messages.SignalPersistedMessage;
import com.stokr.execution.risk.RiskContextFactory;
import com.stokr.execution.service.ExecutionService;
import com.stokr.oms.domain.ExecutionEventType;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.service.ExecutionEventAppendService;
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
    private final ExecutionEventAppendService executionEventAppendService;
    private final RiskContextFactory riskContextFactory;

    @Value("${stokr.risk.zone:Asia/Kolkata}")
    private String riskZone;

    @Value("${stokr.strategy.default-quantity:1}")
    private BigDecimal defaultQuantity;

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
        if (mode == ExecutionMode.LIVE) {
            LiveTraderEligibilityResult gate =
                    liveTradingTraderEligibilityService.evaluateForLiveOrder(signal.getUserId(), strategyKey, "ZERODHA");
            if (!gate.allowed()) {
                log.warn(
                        "live.order.blocked.pre_signal signalId={} reason={}",
                        signal.getId(),
                        gate.reasonCode()
                );
                riskEventRecorder.record(signal.getUserId(), null, gate.reasonCode(), "REJECT", gate.message());
                notifyEligibility(signal.getUserId(), gate);
                return;
            }
        } else {
            LiveTraderEligibilityResult paper = liveTradingTraderEligibilityService.evaluateForPaperTrading(strategyKey);
            if (!paper.allowed()) {
                log.warn(
                        "paper.order.blocked.pre_signal signalId={} reason={}",
                        signal.getId(),
                        paper.reasonCode()
                );
                riskEventRecorder.record(signal.getUserId(), null, paper.reasonCode(), "REJECT", paper.message());
                notifyEligibility(signal.getUserId(), paper);
                return;
            }
        }

        OmsOrder draft = buildDraftFromSignal(signal, mode);
        OmsOrder order = orderLifecycleService.createOrGetIdempotent(signal.getUserId(), idempotencyKey, draft);
        if (order.getState() != OrderState.CREATED) {
            log.info("order.idempotent.hit orderId={} state={}", order.getId(), order.getState());
            return;
        }

        executionEventAppendService.append(order, ExecutionEventType.SIGNAL_GENERATED, Map.of(
                "signalId", signal.getId().toString(),
                "symbol", signal.getSymbol() != null ? signal.getSymbol() : ""
        ));

        executionEventAppendService.append(order, ExecutionEventType.ORDER_REQUESTED, Map.of(
                "symbol", signal.getSymbol() != null ? signal.getSymbol() : "",
                "side", mapSide(signal),
                "executionMode", mode.name()
        ));

        order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        Instant evalInstant = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp() : Instant.now();
        BigDecimal atrRatio = atrToCloseRatio(signal);
        RiskContext ctx = riskContextFactory.build(signal.getUserId(), order, zone, evalInstant, atrRatio);

        RiskDecision decision = riskEngineService.evaluate(ctx);
        riskEvaluationTraceService.record(ctx, decision, order.getId());
        if (!decision.allowed()) {
            riskEventRecorder.record(
                    signal.getUserId(),
                    order.getId(),
                    decision.reasonCode() != null ? decision.reasonCode() : "RISK",
                    "REJECT",
                    decision.message()
            );
            order = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
            executionEventAppendService.append(order, ExecutionEventType.EXECUTION_REJECTED, Map.of(
                    "phase", "RISK",
                    "reason", decision.message() != null ? decision.message() : ""
            ));
            return;
        }

        executionEventAppendService.append(order, ExecutionEventType.RISK_CHECK_PASSED, Map.of(
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
    }

    private static long fillDeterminismKey(StrategySignalEntity signal) {
        long k = signal.getCandleTimestamp() != null ? signal.getCandleTimestamp().toEpochMilli() : 0L;
        k ^= signal.getId().getMostSignificantBits();
        k ^= signal.getId().getLeastSignificantBits();
        k ^= signal.getUserId().getMostSignificantBits();
        return k;
    }

    private OmsOrder buildDraftFromSignal(StrategySignalEntity signal, ExecutionMode mode) {
        OmsOrder o = new OmsOrder();
        o.setSignalId(signal.getId());
        o.setStrategyKey(signal.getStrategyName() != null ? signal.getStrategyName() : StrategySignalEntity.STRATEGY_KEY);
        o.setExecutionMode(mode);
        o.setSymbol(signal.getSymbol());
        o.setSide(mapSide(signal));
        o.setOrderType("MARKET");
        o.setQuantity(signal.getSuggestedQty() != null ? signal.getSuggestedQty() : defaultQuantity);
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
        return ExecutionMode.SIMULATED;
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
}
