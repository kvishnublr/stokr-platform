package com.stokr.execution.service;

import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.oms.service.OrderLifecycleService;
import com.stokr.strategy.service.SignalManualExitSuppressionService;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PositionExitOrchestratorServiceTest {

    @Mock private OmsOrderRepository omsOrderRepository;
    @Mock private OrderLifecycleService orderLifecycleService;
    @Mock private PortfolioPositionRepository portfolioPositionRepository;
    @Mock private TraderExecutionModePreferenceService executionModePreferenceService;
    @Mock private OrderPlacementService orderPlacementService;
    @Mock private BrokerPositionTruthService brokerPositionTruthService;
    @Mock private SignalManualExitSuppressionService manualExitSuppressionService;
    @Mock private ReconciliationEventRepository reconciliationEventRepository;

    @InjectMocks
    private PositionExitOrchestratorService service;

    @Test
    void flattenSegmentPlacesOnlyMatchingBrokerPosition() {
        UUID userId = UUID.randomUUID();
        when(omsOrderRepository.findAllByUserIdAndDeletedFalseAndStateIn(eq(userId), any()))
                .thenReturn(List.of());

        when(brokerPositionTruthService.snapshot(userId)).thenReturn(new BrokerPositionTruthSnapshot(
                com.stokr.execution.broker.BrokerPositionTruthSyncState.VERIFIED,
                Instant.now(),
                3L,
                true,
                List.of(
                        new BrokerPositionTruthSnapshot.BrokerTruthPositionRow(
                                "NSE:INFY", new BigDecimal("2"), new BigDecimal("2"),
                                new BigDecimal("1500"), BigDecimal.ZERO, BigDecimal.ZERO, "MIS", "SYNCED"
                        ),
                        new BrokerPositionTruthSnapshot.BrokerTruthPositionRow(
                                "CRUDEOIL24JUN", new BigDecimal("1"), new BigDecimal("1"),
                                new BigDecimal("8000"), BigDecimal.ZERO, BigDecimal.ZERO, "MIS", "SYNCED"
                        )
                ),
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                0,
                "ok"
        ));

        OmsOrder placed = new OmsOrder();
        placed.setId(UUID.randomUUID());
        placed.setState(OrderState.PENDING_SUBMISSION);
        placed.setExecutionMode(ExecutionMode.LIVE);
        when(orderPlacementService.place(eq(userId), any())).thenReturn(placed);
        when(manualExitSuppressionService.suppressAutoExitForSymbol(eq(userId), eq("NSE:INFY"), any()))
                .thenReturn(1);

        Map<String, Object> result = service.flattenSegment(userId, "NSE", "MARKET_CLOSE_AUTO_EXIT", "market close");

        assertThat(result.get("ordersCreated")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("results");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("symbol")).isEqualTo("NSE:INFY");
        assertThat(rows.getFirst().get("side")).isEqualTo("SELL");
        assertThat(rows.getFirst().get("mode")).isEqualTo("LIVE");

        ArgumentCaptor<com.stokr.execution.dto.CreateOrderRequest> captor =
                ArgumentCaptor.forClass(com.stokr.execution.dto.CreateOrderRequest.class);
        verify(orderPlacementService).place(eq(userId), captor.capture());
        assertThat(captor.getValue().guardMode()).isEqualTo("EXIT_SAFE");
        assertThat(captor.getValue().exitOrder()).isTrue();
        assertThat(captor.getValue().brokerVendor()).isEqualTo("ZERODHA");
    }
}
