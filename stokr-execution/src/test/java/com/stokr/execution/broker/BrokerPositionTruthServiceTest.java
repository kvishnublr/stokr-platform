package com.stokr.execution.broker;

import com.stokr.execution.guard.ExecutionGuardMode;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import com.stokr.oms.reconciliation.BrokerReconciliationService;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.service.SignalManualExitSuppressionService;
import com.stokr.user.broker.ZerodhaBrokerOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrokerPositionTruthServiceTest {

    @Mock
    private ZerodhaBrokerOperationsService zerodhaBrokerOperationsService;
    @Mock
    private OmsExecutionRepository omsExecutionRepository;
    @Mock
    private OmsOrderRepository omsOrderRepository;
    @Mock
    private ReconciliationEventRepository reconciliationEventRepository;
    @Mock
    private StrategyInstanceRepository strategyInstanceRepository;
    @Mock
    private StrategySignalRepository strategySignalRepository;
    @Mock
    private BrokerReconciliationService brokerReconciliationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private SignalManualExitSuppressionService manualExitSuppressionService;

    @InjectMocks
    private BrokerPositionTruthService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "staleMs", 60_000L);
        ReflectionTestUtils.setField(service, "blockExitMinutes", 30L);
        ReflectionTestUtils.setField(service, "externalExitConfirmSeconds", 60L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateExitGuardDetectsBuyExitForShort() {
        BrokerPositionTruthSnapshot snap = new BrokerPositionTruthSnapshot(
                BrokerPositionTruthSyncState.VERIFIED,
                Instant.now(),
                1L,
                true,
                List.of(new BrokerPositionTruthSnapshot.BrokerTruthPositionRow(
                        "NSE:INFY",
                        new BigDecimal("-2"),
                        new BigDecimal("-2"),
                        new BigDecimal("1500"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "MIS",
                        "SYNCED"
                )),
                List.of(),
                Set.of(),
                Set.of(),
                0,
                "ok"
        );
        ConcurrentHashMap<UUID, BrokerPositionTruthSnapshot> cache =
                (ConcurrentHashMap<UUID, BrokerPositionTruthSnapshot>) ReflectionTestUtils.getField(service, "cache");
        cache.put(userId, snap);

        com.stokr.oms.domain.OmsOrder pendingExit = new com.stokr.oms.domain.OmsOrder();
        pendingExit.setUserId(userId);
        pendingExit.setSymbol("NSE:INFY");
        pendingExit.setSide("BUY");
        pendingExit.setState(OrderState.SUBMITTED);

        when(omsOrderRepository.findAllLiveActiveOrders(any())).thenReturn(List.of(pendingExit));

        var violations = service.validateForExecution(
                userId, "NSE:INFY", "BUY", ExecutionGuardMode.EXIT_SAFE, Instant.now());

        assertThat(violations).anyMatch(v -> "DUPLICATE_EXIT".equals(v.code()));
    }

    @Test
    void brokerExitRequiresRepeatConfirmationBeforeSafetyActions() {
        when(zerodhaBrokerOperationsService.status(userId)).thenReturn(
                new ZerodhaBrokerOperationsService.BrokerStatusDto(
                        true, "Zerodha", true, Instant.now(), "DS8838",
                        "tester", "tester@example.com", "{}", "HEALTHY", true, false, null
                )
        );
        when(zerodhaBrokerOperationsService.fetchBrokerPositionDetails(userId)).thenReturn(List.of());
        when(omsExecutionRepository.computeLiveNetQtyBySymbol(userId)).thenReturn(
                List.<Object[]>of(new Object[]{"NSE:INFY", new BigDecimal("1")})
        );
        when(strategyInstanceRepository.findAllForUserWithDefinition(userId)).thenReturn(List.of());

        service.syncUser(userId);
        verify(manualExitSuppressionService, never()).suppressAutoExitForSymbol(eq(userId), eq("NSE:INFY"), any());

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Instant> pending =
                (ConcurrentHashMap<String, Instant>) ReflectionTestUtils.getField(service, "pendingExternalBrokerExits");
        pending.put(userId + ":NSE:INFY", Instant.now().minusSeconds(120));

        service.syncUser(userId);

        verify(manualExitSuppressionService).suppressAutoExitForSymbol(eq(userId), eq("NSE:INFY"), any());
    }
}
