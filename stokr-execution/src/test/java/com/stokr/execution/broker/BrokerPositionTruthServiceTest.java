package com.stokr.execution.broker;

import com.stokr.execution.guard.ExecutionGuardMode;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.reconciliation.ReconciliationEventRepository;
import com.stokr.oms.reconciliation.BrokerReconciliationService;
import com.stokr.oms.service.ExecutionLedgerService;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
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
    private ExecutionLedgerService executionLedgerService;
    @Mock
    private PortfolioAccountingService portfolioAccountingService;
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
        when(omsExecutionRepository.findLiveForUserAndSymbolOrdered(userId, "NSE:INFY"))
                .thenReturn(List.of(liveFill(userId, "NSE:INFY", "BUY", "1500.00")));
        when(omsOrderRepository.findByUserIdAndIdempotencyKeyAndDeletedFalse(eq(userId), anyString()))
                .thenReturn(Optional.empty());
        when(omsOrderRepository.save(any(OmsOrder.class))).thenAnswer(invocation -> {
            OmsOrder saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(executionLedgerService.appendExecution(
                any(OmsOrder.class),
                isNull(),
                any(BigDecimal.class),
                any(BigDecimal.class),
                eq("EXTERNAL_EXIT"),
                any(Instant.class),
                eq(0L),
                eq(BigDecimal.ZERO),
                eq(BigDecimal.ZERO),
                any(BigDecimal.class),
                eq("BROKER_TRUTH"),
                isNull()
        )).thenReturn(new OmsExecution());

        service.syncUser(userId);
        verify(manualExitSuppressionService, never()).suppressAutoExitForSymbol(eq(userId), eq("NSE:INFY"), any());

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Instant> pending =
                (ConcurrentHashMap<String, Instant>) ReflectionTestUtils.getField(service, "pendingExternalBrokerExits");
        pending.put(userId + ":NSE:INFY", Instant.now().minusSeconds(120));

        service.syncUser(userId);

        verify(manualExitSuppressionService).suppressAutoExitForSymbol(eq(userId), eq("NSE:INFY"), any());
    }

    @Test
    void confirmedBrokerExitRecordsInternalLedgerOffset() {
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

        OmsOrder entry = new OmsOrder();
        entry.setId(UUID.randomUUID());
        entry.setUserId(userId);
        entry.setSymbol("NSE:INFY");
        entry.setSide("BUY");
        entry.setExecutionMode(ExecutionMode.LIVE);
        entry.setState(OrderState.FILLED);

        OmsExecution fill = new OmsExecution();
        fill.setId(UUID.randomUUID());
        fill.setOrder(entry);
        fill.setAvgPrice(new BigDecimal("1500.25"));
        fill.setFilledQty(BigDecimal.ONE);
        when(omsExecutionRepository.findLiveForUserAndSymbolOrdered(userId, "NSE:INFY"))
                .thenReturn(List.of(fill));
        when(omsOrderRepository.findByUserIdAndIdempotencyKeyAndDeletedFalse(eq(userId), anyString()))
                .thenReturn(Optional.empty());
        when(omsOrderRepository.save(any(OmsOrder.class))).thenAnswer(invocation -> {
            OmsOrder saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(executionLedgerService.appendExecution(
                any(OmsOrder.class),
                isNull(),
                eq(BigDecimal.ONE),
                eq(new BigDecimal("1500.25")),
                eq("EXTERNAL_EXIT"),
                any(Instant.class),
                eq(0L),
                eq(BigDecimal.ZERO),
                eq(BigDecimal.ZERO),
                eq(new BigDecimal("1500.25")),
                eq("BROKER_TRUTH"),
                isNull()
        )).thenReturn(new OmsExecution());

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Instant> pending =
                (ConcurrentHashMap<String, Instant>) ReflectionTestUtils.getField(service, "pendingExternalBrokerExits");
        pending.put(userId + ":NSE:INFY", Instant.now().minusSeconds(120));

        service.syncUser(userId);

        verify(omsOrderRepository).save(org.mockito.ArgumentMatchers.argThat(order ->
                order.getUserId().equals(userId)
                        && "NSE:INFY".equals(order.getSymbol())
                        && "SELL".equals(order.getSide())
                        && "EXTERNAL_EXIT".equals(order.getOrderType())
                        && BigDecimal.ONE.compareTo(order.getQuantity()) == 0
                        && order.getState() == OrderState.FILLED
                        && order.getExecutionMode() == ExecutionMode.LIVE
                        && "EXTERNAL_BROKER_EXIT".equals(order.getExecutionLinkage())
        ));
        verify(executionLedgerService, atLeastOnce()).appendExecution(
                any(OmsOrder.class),
                isNull(),
                eq(BigDecimal.ONE),
                eq(new BigDecimal("1500.25")),
                eq("EXTERNAL_EXIT"),
                any(Instant.class),
                eq(0L),
                eq(BigDecimal.ZERO),
                eq(BigDecimal.ZERO),
                eq(new BigDecimal("1500.25")),
                eq("BROKER_TRUTH"),
                isNull()
        );
        verify(portfolioAccountingService).applyFill(userId, "NSE:INFY", "EXTERNAL_BROKER_EXIT");
        verify(manualExitSuppressionService).suppressAutoExitForSymbol(eq(userId), eq("NSE:INFY"), any());
    }

    private static OmsExecution liveFill(UUID userId, String symbol, String side, String price) {
        OmsOrder order = new OmsOrder();
        order.setId(UUID.randomUUID());
        order.setUserId(userId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setExecutionMode(ExecutionMode.LIVE);
        order.setState(OrderState.FILLED);

        OmsExecution execution = new OmsExecution();
        execution.setId(UUID.randomUUID());
        execution.setOrder(order);
        execution.setAvgPrice(new BigDecimal(price));
        execution.setFilledQty(BigDecimal.ONE);
        return execution;
    }
}
