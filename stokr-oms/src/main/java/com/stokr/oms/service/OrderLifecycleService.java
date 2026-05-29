package com.stokr.oms.service;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.registry.BrokerAdapterRegistry;
import com.stokr.broker.safety.BrokerLiveOrderGuard;
import com.stokr.common.simulation.SimulationModeService;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.ConflictException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.execution.OrderStateMachine;
import com.stokr.oms.repository.OmsOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderLifecycleService {

    private static final Set<OrderState> STUCK_STATES = Set.of(
            OrderState.RISK_CHECK, OrderState.PENDING_SUBMISSION,
            OrderState.SUBMITTED, OrderState.VALIDATED
    );

    private final OmsOrderRepository orderRepository;
    private final BrokerAdapterRegistry brokerAdapterRegistry;
    private final SimulationModeService simulationModeService;
    private final BrokerLiveOrderGuard brokerLiveOrderGuard;

    @Transactional
    public OmsOrder createOrGetIdempotent(UUID userId, String idempotencyKey, OmsOrder draft) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<OmsOrder> existing =
                    orderRepository.findByUserIdAndIdempotencyKeyAndDeletedFalse(userId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return persistNew(userId, idempotencyKey, draft);
    }

    private OmsOrder persistNew(UUID userId, String idempotencyKey, OmsOrder draft) {
        draft.setUserId(userId);
        draft.setIdempotencyKey(idempotencyKey);
        draft.setCorrelationId(CorrelationIdHolder.get());
        if (draft.getState() == null) {
            draft.setState(OrderState.CREATED);
        }
        return orderRepository.save(draft);
    }

    @Transactional(readOnly = true)
    public OmsOrder getRequired(UUID orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
    }

    @Transactional
    public OmsOrder transition(UUID orderId, OrderState newState, String rejectReason) {
        OmsOrder order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        OrderStateMachine.validate(order.getState(), newState);
        order.setState(newState);
        order.setRejectReason(rejectReason);
        return orderRepository.save(order);
    }

    /**
     * Admin operation: force-transitions all orders stuck in pre-terminal states for longer than
     * {@code stuckMinutes} into FAILED state. Safe to call at any time.
     */
    @Transactional
    public int forceExpireStuckOrders(int stuckMinutes) {
        Instant before = Instant.now().minus(stuckMinutes, ChronoUnit.MINUTES);
        List<OmsOrder> stuck = orderRepository.findStuckOrders(STUCK_STATES, before);
        if (stuck.isEmpty()) return 0;
        for (OmsOrder o : stuck) {
            try {
                String stuckStateName = o.getState().name();
                o.setState(OrderState.FAILED);
                o.setRejectReason("admin_force_expired: stuck in " + stuckStateName + " for >" + stuckMinutes + "m");
                orderRepository.save(o);
                log.warn("oms.force_expire orderId={} symbol={} state={}", o.getId(), o.getSymbol(), o.getState());
            } catch (Exception ex) {
                log.error("oms.force_expire_error orderId={} {}", o.getId(), ex.getMessage());
            }
        }
        log.info("oms.force_expire_done count={}", stuck.size());
        return stuck.size();
    }

    /**
     * LIVE routing only: {@link OrderState#PENDING_SUBMISSION} → {@link OrderState#SUBMITTED} via broker adapter.
     * Broker rejections are caught and recorded as {@link OrderState#FAILED} — callers never see an exception,
     * so the enclosing transaction always commits and the failure reason is persisted on the order.
     */
    @Transactional
    public OmsOrder submitToBroker(OmsOrder order, String brokerVendor, String apiKey, String accessToken) {
        if (order.getExecutionMode() != ExecutionMode.LIVE) {
            throw new ConflictException("Broker routing only supported for LIVE execution mode");
        }
        if (order.getState() != OrderState.PENDING_SUBMISSION) {
            throw new ConflictException("Order must be PENDING_SUBMISSION before broker submission");
        }
        OmsOrder submitted = transition(order.getId(), OrderState.SUBMITTED, null);
        String effectiveVendor = simulationModeService.simulateBrokerExecution()
                ? simulationModeService.brokerVendor()
                : brokerVendor;
        brokerLiveOrderGuard.assertLiveOrderAllowed(effectiveVendor);
        BrokerAdapter adapter = brokerAdapterRegistry.get(effectiveVendor);
        BrokerOrderRequest req = new BrokerOrderRequest(
                submitted.getSymbol(),
                submitted.getSide(),
                submitted.getOrderType(),
                submitted.getQuantity(),
                submitted.getLimitPrice(),
                submitted.getId() != null ? submitted.getId().toString() : null,
                apiKey,
                accessToken,
                null,   // exchange — ZerodhaAdapter parses from symbol
                null    // product — resolved per exchange in ZerodhaAdapter (MCX=NRML, NSE=MIS)
        );
        try {
            BrokerOrderResponse res = adapter.placeOrder(req);
            submitted.setBrokerVendor(effectiveVendor);
            submitted.setBrokerOrderId(res.brokerOrderId());
            if (res.externalOrderId() != null && !res.externalOrderId().isBlank()) {
                submitted.setBrokerExternalOrderId(res.externalOrderId().trim());
            }
            String status = res.status() != null ? res.status().trim().toUpperCase() : "";
            if ("REJECTED".equals(status) || "CANCELLED".equals(status)) {
                return transition(submitted.getId(), OrderState.FAILED, "BROKER_REJECTED: " + status);
            }
            if ("PARTIALLY_FILLED".equals(status)) {
                submitted.setState(OrderState.PARTIALLY_FILLED);
                return orderRepository.save(submitted);
            }
            return orderRepository.save(submitted);
        } catch (Exception ex) {
            // Catch all broker failures — persist as FAILED so callers see exact rejection reason.
            // Never rethrow here: the enclosing transaction must commit so the signal + order are saved.
            String reason = ex.getMessage() != null
                    ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 450))
                    : "broker_error";
            log.error("broker.submit.failed orderId={} vendor={} reason={}",
                    submitted.getId(), effectiveVendor, reason);
            return transition(submitted.getId(), OrderState.FAILED, "BROKER_REJECTED: " + reason);
        }
    }

    /** Backward-compatible overload for non-live paths that don't carry broker credentials. */
    @Transactional
    public OmsOrder submitToBroker(OmsOrder order, String brokerVendor) {
        return submitToBroker(order, brokerVendor, null, null);
    }
}
