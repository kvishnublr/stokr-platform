package com.stokr.execution.service;

import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.execution.dto.CreateOrderRequest;
import com.stokr.execution.guard.ExecutionAttemptKey;
import com.stokr.execution.guard.ExecutionAttemptTracker;
import com.stokr.execution.guard.ExecutionContext;
import com.stokr.execution.guard.ExecutionGuardMode;
import com.stokr.execution.guard.ExecutionGuardOutcome;
import com.stokr.execution.guard.ExecutionGuardResult;
import com.stokr.execution.guard.ExecutionGuardService;
import com.stokr.execution.guard.ExecutionGuardTelemetryService;
import com.stokr.execution.guard.ExecutionTimelineService;
import com.stokr.execution.guard.SignalExecutionMetadata;
import com.stokr.execution.risk.RiskContextFactory;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.service.RiskEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderPlacementService {

    private final OrderLifecycleService orderLifecycleService;
    private final RiskEngineService riskEngineService;
    private final ExecutionService executionService;
    private final RiskContextFactory riskContextFactory;
    private final ExecutionGuardService executionGuardService;
    private final ExecutionAttemptTracker executionAttemptTracker;
    private final ExecutionGuardTelemetryService executionGuardTelemetryService;
    private final ExecutionTimelineService executionTimelineService;

    @Value("${stokr.risk.zone:Asia/Kolkata}")
    private String riskZone;

    @Transactional
    public OmsOrder place(UUID userId, CreateOrderRequest req) {
        ExecutionMode mode = req.executionMode() == null ? ExecutionMode.SIMULATED : req.executionMode();
        Instant now = Instant.now();

        OmsOrder draft = new OmsOrder();
        draft.setSymbol(req.symbol());
        draft.setSide(req.side());
        draft.setOrderType(req.orderType());
        draft.setQuantity(req.quantity());
        draft.setLimitPrice(req.limitPrice());
        draft.setStrategyKey(req.strategyKey());
        draft.setSignalId(req.signalId());
        draft.setEntryReferencePrice(req.signalReferencePrice());
        draft.setExecutionMode(mode);
        String broker = req.brokerVendor();
        if (broker == null || broker.isBlank()) {
            broker = mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM";
        }
        draft.setBrokerVendor(broker);

        OmsOrder order = orderLifecycleService.createOrGetIdempotent(userId, req.idempotencyKey(), draft);
        executionTimelineService.append(
                order.getId(),
                null,
                userId,
                req.signalId(),
                req.strategyKey(),
                req.symbol(),
                "SIGNAL_RECEIVED",
                now,
                java.util.Map.of("executionMode", mode.name(), "orderType", req.orderType(), "quantity", req.quantity()),
                null,
                java.util.Map.of(),
                null,
                "INFO"
        );
        if (order.getState() != OrderState.CREATED) {
            return order;
        }

        if (mode == ExecutionMode.LIVE) {
            ExecutionGuardMode guardMode = resolveGuardMode(req);
            SignalExecutionMetadata signalMeta = new SignalExecutionMetadata(
                    req.signalId(),
                    req.signalGeneratedAt(),
                    req.signalReferencePrice() != null ? req.signalReferencePrice() : req.limitPrice(),
                    req.timeframe()
            );
            ExecutionAttemptKey attemptKey = ExecutionAttemptKey.of(
                    req.signalId(),
                    req.symbol(),
                    req.side(),
                    req.strategyKey(),
                    req.timeframe(),
                    now,
                    10L
            );
            if (!executionAttemptTracker.markIfFirst(attemptKey, now)) {
                OmsOrder rejected = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, "Duplicate execution attempt blocked");
                ExecutionGuardResult duplicateResult = new ExecutionGuardResult(
                        ExecutionGuardOutcome.HARD_FAIL,
                        null,
                        null,
                        java.util.List.of(),
                        0
                );
                executionGuardTelemetryService.recordGuardEvent(userId, rejected.getId(), new ExecutionContext(
                        userId, now, req.strategyKey(), req.symbol(), req.side(), mode, guardMode, req, signalMeta
                ), duplicateResult);
                return rejected;
            }

            ExecutionContext ctx = new ExecutionContext(
                    userId,
                    now,
                    req.strategyKey(),
                    req.symbol(),
                    req.side(),
                    mode,
                    guardMode,
                    req,
                    signalMeta
            );
            ExecutionGuardResult guard = executionGuardService.validate(ctx);
            if (guard.outcome() == ExecutionGuardOutcome.HARD_FAIL
                    || (guard.outcome() == ExecutionGuardOutcome.SOFT_FAIL && !guard.policy().allowSoftFailExecution())) {
                String msg = guard.violations().isEmpty() ? "Execution blocked by guard policy" : guard.violations().getFirst().message();
                OmsOrder rejected = orderLifecycleService.transition(order.getId(), OrderState.REJECTED, msg);
                executionGuardTelemetryService.recordGuardEvent(userId, rejected.getId(), ctx, guard);
                return rejected;
            }
            executionGuardTelemetryService.recordGuardEvent(userId, order.getId(), ctx, guard);
        }

        order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        RiskContext ctx = riskContextFactory.build(userId, order, zone, Instant.now(), null);

        RiskDecision decision = riskEngineService.evaluate(ctx);
        if (!decision.allowed()) {
            return orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
        }

        order = orderLifecycleService.transition(order.getId(), OrderState.PENDING_SUBMISSION, null);
        executionTimelineService.append(
                order.getId(),
                null,
                userId,
                req.signalId(),
                req.strategyKey(),
                req.symbol(),
                "BROKER_REQUEST_SUBMIT",
                Instant.now(),
                java.util.Map.of("brokerVendor", order.getBrokerVendor(), "state", OrderState.PENDING_SUBMISSION.name()),
                null,
                java.util.Map.of(),
                null,
                "INFO"
        );

        executionService.dispatch(
                new ExecutionDispatchMessage(
                        order.getId(),
                        userId,
                        null,
                        order.getBrokerVendor(),
                        0,
                        null,
                        mode.name(),
                        order.getId().getMostSignificantBits() ^ order.getId().getLeastSignificantBits(),
                        order.getCreatedAt()
                ),
                false
        );

        return orderLifecycleService.getRequired(order.getId());
    }

    private static ExecutionGuardMode resolveGuardMode(CreateOrderRequest req) {
        if ("EXIT_SAFE".equalsIgnoreCase(req.guardMode())) return ExecutionGuardMode.EXIT_SAFE;
        if (Boolean.TRUE.equals(req.exitOrder())) return ExecutionGuardMode.EXIT_SAFE;
        return ExecutionGuardMode.ENTRY_STRICT;
    }
}
