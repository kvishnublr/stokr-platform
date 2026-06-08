package com.stokr.oms.reconciliation;

import com.stokr.broker.api.BrokerAdapter;
import com.stokr.broker.api.BrokerUserPositionsSource;
import com.stokr.broker.model.BrokerPosition;
import com.stokr.broker.registry.BrokerAdapterRegistry;
import com.stokr.common.events.ExecutionAlertEvent;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.util.OmsSymbolNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerReconciliationService {

    private static final Collection<OrderState> LIVE_ACTIVE_STATES = List.of(
            OrderState.CREATED, OrderState.VALIDATED, OrderState.RISK_CHECK,
            OrderState.PENDING_SUBMISSION, OrderState.SUBMITTED, OrderState.ACCEPTED,
            OrderState.PARTIALLY_FILLED
    );

    private final OmsOrderRepository omsOrderRepository;
    private final OmsExecutionRepository omsExecutionRepository;
    private final ReconciliationEventRepository reconciliationEventRepository;
    private final BrokerAdapterRegistry brokerAdapterRegistry;
    private final List<BrokerUserPositionsSource> brokerUserPositionsSources;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${stokr.reconciliation.interval-ms:30000}")
    public void reconcileAll() {
        List<OmsOrder> liveOrders = omsOrderRepository.findAllLiveActiveOrders(LIVE_ACTIVE_STATES);

        Map<String, List<OmsOrder>> byVendor = liveOrders.stream()
                .filter(o -> o.getBrokerVendor() != null && o.getUserId() != null)
                .collect(Collectors.groupingBy(o -> o.getUserId() + ":" + o.getBrokerVendor()));

        for (Map.Entry<String, List<OmsOrder>> entry : byVendor.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            try {
                UUID userId = UUID.fromString(parts[0]);
                String vendor = parts[1];
                reconcileUser(userId, vendor);
            } catch (Exception e) {
                log.warn("reconciliation.user_failed key={} error={}", entry.getKey(), e.getMessage());
            }
        }
    }

    @Transactional
    public void reconcileUser(UUID userId, String brokerVendor) {
        BrokerAdapter adapter;
        try {
            adapter = brokerAdapterRegistry.get(brokerVendor);
        } catch (Exception e) {
            log.warn("reconciliation.no_adapter vendor={}", brokerVendor);
            return;
        }

        List<BrokerPosition> brokerPositions = resolveBrokerPositions(userId, brokerVendor, adapter);
        if (brokerPositions == null) {
            return;
        }

        Map<String, BigDecimal> brokerBySymbol = brokerPositions.stream()
                .collect(Collectors.toMap(
                        p -> OmsSymbolNormalizer.normalize(p.symbol()),
                        BrokerPosition::quantity,
                        BigDecimal::add));
        Map<String, BigDecimal> internalBySymbol = omsExecutionRepository.computeLiveNetQtyBySymbol(userId).stream()
                .collect(Collectors.toMap(
                        row -> OmsSymbolNormalizer.normalize(String.valueOf(row[0])),
                        row -> (BigDecimal) row[1]
                ));

        if (brokerBySymbol.isEmpty() && internalBySymbol.isEmpty()) {
            return;
        }

        for (Map.Entry<String, BigDecimal> brokerEntry : brokerBySymbol.entrySet()) {
            String symbol = brokerEntry.getKey();
            BigDecimal brokerQty = brokerEntry.getValue();
            BigDecimal internalQty = internalBySymbol.getOrDefault(symbol, BigDecimal.ZERO);
            if (internalQty.compareTo(BigDecimal.ZERO) == 0) {
                persist(userId, brokerVendor, symbol, "ORPHAN_BROKER_POSITION", brokerQty, BigDecimal.ZERO);
            } else if (brokerQty.compareTo(internalQty) != 0) {
                persist(userId, brokerVendor, symbol, "QUANTITY_MISMATCH", brokerQty, internalQty);
            }
        }

        for (Map.Entry<String, BigDecimal> internalEntry : internalBySymbol.entrySet()) {
            String symbol = internalEntry.getKey();
            if (!brokerBySymbol.containsKey(symbol)) {
                persist(userId, brokerVendor, symbol, "GHOST_INTERNAL_POSITION",
                        BigDecimal.ZERO, internalEntry.getValue());
            }
        }
    }

    public void triggerForUser(UUID userId, String brokerVendor) {
        reconcileUser(userId, brokerVendor);
    }

    private List<BrokerPosition> resolveBrokerPositions(UUID userId, String brokerVendor, BrokerAdapter adapter) {
        for (BrokerUserPositionsSource source : brokerUserPositionsSources) {
            if (!source.supportsVendor(brokerVendor)) {
                continue;
            }
            try {
                return source.fetchPositions(userId, brokerVendor).orElse(List.of());
            } catch (Exception e) {
                log.warn("reconciliation.broker_query_failed vendor={} user={} error={}",
                        brokerVendor, userId, e.getMessage());
                return null;
            }
        }
        try {
            return adapter.getPositions();
        } catch (Exception e) {
            log.warn("reconciliation.broker_query_failed vendor={} user={} error={}", brokerVendor, userId, e.getMessage());
            return null;
        }
    }

    private void persist(UUID userId, String brokerVendor, String symbol, String type,
                          BigDecimal brokerQty, BigDecimal internalQty) {
        ReconciliationEvent ev = new ReconciliationEvent();
        ev.setUserId(userId);
        ev.setBrokerVendor(brokerVendor);
        ev.setSymbol(symbol);
        ev.setDiscrepancyType(type);
        ev.setBrokerQty(brokerQty);
        ev.setInternalQty(internalQty);
        ev.setDelta(brokerQty.subtract(internalQty));
        reconciliationEventRepository.save(ev);
        log.warn("reconciliation.discrepancy type={} symbol={} broker={} internal={}", type, symbol, brokerQty, internalQty);
        eventPublisher.publishEvent(new ExecutionAlertEvent(
                "RECONCILIATION_ALERT", null, symbol, null, userId,
                "RECONCILIATION: " + symbol + " broker=" + brokerQty + " internal=" + internalQty));
    }
}
