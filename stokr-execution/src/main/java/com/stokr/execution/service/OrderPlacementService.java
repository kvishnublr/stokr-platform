package com.stokr.execution.service;

import com.stokr.common.pipeline.messages.ExecutionDispatchMessage;
import com.stokr.execution.dto.CreateOrderRequest;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderPlacementService {

    private final OrderLifecycleService orderLifecycleService;
    private final RiskEngineService riskEngineService;
    private final ExecutionService executionService;

    @Value("${stokr.risk.zone:Asia/Kolkata}")
    private String riskZone;

    @Transactional
    public OmsOrder place(UUID userId, CreateOrderRequest req) {
        ExecutionMode mode = req.executionMode() == null ? ExecutionMode.SIMULATED : req.executionMode();

        OmsOrder draft = new OmsOrder();
        draft.setSymbol(req.symbol());
        draft.setSide(req.side());
        draft.setOrderType(req.orderType());
        draft.setQuantity(req.quantity());
        draft.setLimitPrice(req.limitPrice());
        draft.setStrategyKey(req.strategyKey());
        draft.setExecutionMode(mode);
        String broker = req.brokerVendor();
        if (broker == null || broker.isBlank()) {
            broker = mode == ExecutionMode.LIVE ? "ZERODHA" : "SIM";
        }
        draft.setBrokerVendor(broker);

        OmsOrder order = orderLifecycleService.createOrGetIdempotent(userId, req.idempotencyKey(), draft);
        if (order.getState() != OrderState.CREATED) {
            return order;
        }

        order = orderLifecycleService.transition(order.getId(), OrderState.VALIDATING, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.RISK_CHECK, null);

        ZoneId zone = ZoneId.of(riskZone);
        RiskContext ctx = new RiskContext(
                userId,
                order,
                BigDecimal.ZERO,
                0,
                LocalTime.now(zone),
                zone,
                Instant.now().toEpochMilli()
        );

        RiskDecision decision = riskEngineService.evaluate(ctx);
        if (!decision.allowed()) {
            return orderLifecycleService.transition(order.getId(), OrderState.REJECTED, decision.message());
        }

        order = orderLifecycleService.transition(order.getId(), OrderState.ACCEPTED, null);
        order = orderLifecycleService.transition(order.getId(), OrderState.QUEUED, null);

        executionService.dispatch(
                new ExecutionDispatchMessage(
                        order.getId(),
                        userId,
                        null,
                        order.getBrokerVendor(),
                        0,
                        null,
                        mode.name()
                ),
                false
        );

        return orderLifecycleService.getRequired(order.getId());
    }
}
