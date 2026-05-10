package com.stokr.oms.service;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.model.BrokerOrderRequest;
import com.stokr.broker.model.BrokerOrderResponse;
import com.stokr.broker.registry.BrokerAdapterRegistry;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.ConflictException;
import com.stokr.common.exception.NotFoundException;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OmsOrderRepository orderRepository;
    private final BrokerAdapterRegistry brokerAdapterRegistry;

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
        order.setState(newState);
        order.setRejectReason(rejectReason);
        return orderRepository.save(order);
    }

    /**
     * LIVE routing only: moves QUEUED → SENT using broker adapter (immutable broker truth captured separately).
     */
    @Transactional
    public OmsOrder submitToBroker(OmsOrder order, String brokerVendor) {
        if (order.getExecutionMode() != ExecutionMode.LIVE) {
            throw new ConflictException("Broker routing only supported for LIVE execution mode");
        }
        if (order.getState() != OrderState.QUEUED) {
            throw new ConflictException("Order must be QUEUED before broker submission");
        }
        BrokerAdapter adapter = brokerAdapterRegistry.get(brokerVendor);
        BrokerOrderRequest req = new BrokerOrderRequest(
                order.getSymbol(),
                order.getSide(),
                order.getOrderType(),
                order.getQuantity(),
                order.getLimitPrice(),
                order.getId() != null ? order.getId().toString() : null
        );
        BrokerOrderResponse res = adapter.placeOrder(req);
        order.setBrokerVendor(brokerVendor);
        order.setBrokerOrderId(res.brokerOrderId());
        order.setState(OrderState.SENT);
        return orderRepository.save(order);
    }
}
