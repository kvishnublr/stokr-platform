package com.stokr.execution.broker;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.OmsTradeRepository;
import com.stokr.oms.service.ExecutionLedgerService;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.execution.comparison.TradeLifecycleReconciliationService;
import com.stokr.execution.guard.ExecutionGuardTelemetryService;
import com.stokr.oms.trace.ExecutionTraceService;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveBrokerFillSyncServiceTest {

    @Mock
    private OmsOrderRepository orderRepository;
    @Mock
    private OrderLifecycleService orderLifecycleService;
    @Mock
    private ExecutionLedgerService executionLedgerService;
    @Mock
    private OmsTradeRepository tradeRepository;
    @Mock
    private PortfolioAccountingService portfolioAccountingService;
    @Mock
    private ExecutionTraceService executionTraceService;
    @Mock
    private ExecutionGuardTelemetryService executionGuardTelemetryService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;
    @Mock
    private TradeLifecycleReconciliationService tradeLifecycleReconciliationService;

    private LiveBrokerFillSyncService service;

    @BeforeEach
    void setUp() {
        service = new LiveBrokerFillSyncService(
                orderRepository,
                orderLifecycleService,
                executionLedgerService,
                tradeRepository,
                portfolioAccountingService,
                executionTraceService,
                executionGuardTelemetryService,
                eventPublisher,
                zerodhaBrokerOperationsService,
                tradeLifecycleReconciliationService
        );
    }

    @Test
    void syncOrder_includesTestLabOrders() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OmsOrder order = liveTestOrder(userId, orderId, "kite-123", OrderState.ACCEPTED, true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(zerodhaBrokerOperationsService.recentOrders(userId, 300)).thenReturn(List.of(
                new ZerodhaBrokerOperationsService.BrokerOpenOrderDto(
                        "kite-123",
                        null,
                        "NSE",
                        "ITC",
                        "BUY",
                        "MIS",
                        "regular",
                        "MARKET",
                        1,
                        null,
                        "COMPLETE",
                        Instant.now(),
                        "",
                        1,
                        288.65
                )
        ));
        when(executionLedgerService.appendExecution(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new com.stokr.oms.domain.OmsExecution());
        when(orderLifecycleService.transition(orderId, OrderState.FILLED, null)).thenReturn(order);

        service.syncOrder(orderId);

        verify(orderLifecycleService).transition(orderId, OrderState.FILLED, null);
    }

    @Test
    void syncOrder_skipsWhenAlreadyFilled() {
        UUID orderId = UUID.randomUUID();
        OmsOrder order = liveTestOrder(UUID.randomUUID(), orderId, "kite-123", OrderState.FILLED, true);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.syncOrder(orderId);

        verify(zerodhaBrokerOperationsService, never()).recentOrders(any(), eq(300));
    }

    private static OmsOrder liveTestOrder(UUID userId, UUID orderId, String kiteId, OrderState state, boolean testTrade) {
        OmsOrder order = new OmsOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSymbol("NSE:ITC");
        order.setSide("BUY");
        order.setQuantity(BigDecimal.ONE);
        order.setExecutionMode(ExecutionMode.LIVE);
        order.setBrokerVendor("ZERODHA");
        order.setBrokerExternalOrderId(kiteId);
        order.setState(state);
        order.setTestTrade(testTrade);
        order.setCreatedAt(Instant.now());
        return order;
    }
}
