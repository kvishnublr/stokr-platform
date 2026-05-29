package com.stokr.execution.pipeline;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.execution.broker.BrokerPositionTruthSyncState;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignalOutcomeExitServiceTest {

    @Mock
    private StrategySignalRepository signalRepository;
    @Mock
    private OmsOrderRepository omsOrderRepository;
    @Mock
    private OrderPlacementService orderPlacementService;
    @Mock
    private BrokerPositionTruthService brokerPositionTruthService;

    @InjectMocks
    private SignalOutcomeExitService service;

    private final UUID signalId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void enableAutoExit() {
        ReflectionTestUtils.setField(service, "autoExitEnabled", true);
        ReflectionTestUtils.setField(service, "autoExitOnBreakeven", true);
    }

    @Test
    void placesExitOrderOnTargetHit() {
        StrategySignalEntity signal = liveSignal();
        when(signalRepository.findById(signalId)).thenReturn(Optional.of(signal));

        OmsOrder entry = new OmsOrder();
        entry.setId(UUID.randomUUID());
        entry.setUserId(userId);
        entry.setSymbol("NSE:INFY");
        entry.setSide("BUY");
        entry.setQuantity(BigDecimal.ONE);
        entry.setState(OrderState.FILLED);
        entry.setExecutionMode(ExecutionMode.PAPER);
        entry.setStrategyKey("EARLY_BREAKOUT");
        when(omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId))
                .thenReturn(List.of(entry));

        when(brokerPositionTruthService.snapshot(userId)).thenReturn(snapshotWithQty("NSE:INFY", BigDecimal.ONE));

        OmsOrder exit = new OmsOrder();
        exit.setId(UUID.randomUUID());
        exit.setState(OrderState.PENDING_SUBMISSION);
        when(orderPlacementService.place(eq(userId), any(CreateOrderRequest.class))).thenReturn(exit);

        service.onSignalOutcome(new OperationalRealtimeEvent("signal_outcome", Map.of(
                "signalId", signalId.toString(),
                "outcomeStatus", "TARGET_HIT",
                "userId", userId.toString()
        )));

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderPlacementService).place(eq(userId), captor.capture());
        CreateOrderRequest req = captor.getValue();
        assertThat(req.side()).isEqualTo("SELL");
        assertThat(req.exitOrder()).isTrue();
        assertThat(req.guardMode()).isEqualTo("EXIT_SAFE");
        assertThat(req.signalId()).isNull();
        assertThat(req.idempotencyKey()).contains("outcome-exit:" + signalId);
    }

    @Test
    void placesExitOrderOnLiquidityProtection() {
        StrategySignalEntity signal = liveSignal();
        when(signalRepository.findById(signalId)).thenReturn(Optional.of(signal));

        OmsOrder entry = new OmsOrder();
        entry.setId(UUID.randomUUID());
        entry.setUserId(userId);
        entry.setSymbol("NSE:INFY");
        entry.setSide("SELL");
        entry.setQuantity(BigDecimal.ONE);
        entry.setState(OrderState.FILLED);
        entry.setExecutionMode(ExecutionMode.PAPER);
        entry.setStrategyKey("ADV_CASH");
        when(omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(signalId))
                .thenReturn(List.of(entry));

        when(brokerPositionTruthService.snapshot(userId)).thenReturn(snapshotWithQty("NSE:INFY", BigDecimal.ONE.negate()));

        OmsOrder exit = new OmsOrder();
        exit.setId(UUID.randomUUID());
        exit.setState(OrderState.PENDING_SUBMISSION);
        when(orderPlacementService.place(eq(userId), any(CreateOrderRequest.class))).thenReturn(exit);

        service.onSignalOutcome(new OperationalRealtimeEvent("signal_outcome", Map.of(
                "signalId", signalId.toString(),
                "outcomeStatus", "LIQUIDITY_PROTECTION",
                "userId", userId.toString()
        )));

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderPlacementService).place(eq(userId), captor.capture());
        assertThat(captor.getValue().side()).isEqualTo("BUY");
    }

    @Test
    void skipsReplaySignals() {
        StrategySignalEntity signal = liveSignal();
        signal.setSignalSource(SignalProvenance.REPLAY);
        when(signalRepository.findById(signalId)).thenReturn(Optional.of(signal));

        service.onSignalOutcome(new OperationalRealtimeEvent("signal_outcome", Map.of(
                "signalId", signalId.toString(),
                "outcomeStatus", "STOPLOSS_HIT"
        )));

        verify(orderPlacementService, never()).place(any(), any());
    }

    private StrategySignalEntity liveSignal() {
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setId(signalId);
        signal.setUserId(userId);
        signal.setSymbol("NSE:INFY");
        signal.setStrategyName("EARLY_BREAKOUT");
        signal.setSignalType(SignalType.BUY);
        signal.setSignalSource(SignalProvenance.LIVE);
        signal.setStopPrice(new BigDecimal("1450"));
        signal.setTargetPrice(new BigDecimal("1500"));
        signal.setEntryReferencePrice(new BigDecimal("1460"));
        return signal;
    }

    private static BrokerPositionTruthSnapshot snapshotWithQty(String symbol, BigDecimal qty) {
        return new BrokerPositionTruthSnapshot(
                BrokerPositionTruthSyncState.VERIFIED,
                Instant.now(),
                5L,
                true,
                List.of(new BrokerPositionTruthSnapshot.BrokerTruthPositionRow(
                        symbol, qty, qty, new BigDecimal("1460"),
                        BigDecimal.ZERO, BigDecimal.ZERO, "MIS", "SYNCED"
                )),
                List.of(),
                Set.of(),
                Set.of(),
                0,
                "ok"
        );
    }
}
