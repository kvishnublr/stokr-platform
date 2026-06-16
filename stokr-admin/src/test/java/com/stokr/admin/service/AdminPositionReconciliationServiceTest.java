package com.stokr.admin.service;

import com.stokr.auth.repository.AuthUserRepository;
import com.stokr.execution.broker.BrokerPositionTruthService;
import com.stokr.execution.broker.BrokerPositionTruthSnapshot;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.portfolio.PortfolioAccountingService;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategyExecutionConfig;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategyExecutionConfigRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import com.stokr.user.broker.BrokerExecutionCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPositionReconciliationServiceTest {

    private static final UUID TRADER = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    @Mock
    private PortfolioPositionRepository positionRepository;
    @Mock
    private StrategySignalRepository signalRepository;
    @Mock
    private StrategyExecutionConfigRepository configRepository;
    @Mock
    private AuthUserRepository authUserRepository;
    @Mock
    private BrokerExecutionCredentialService brokerCredentials;
    @Mock
    private BrokerPositionTruthService brokerTruthService;
    @Mock
    private PortfolioAccountingService portfolioAccountingService;

    private AdminPositionReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new AdminPositionReconciliationService(
                positionRepository,
                signalRepository,
                configRepository,
                authUserRepository,
                brokerCredentials,
                brokerTruthService,
                portfolioAccountingService);
        ReflectionTestUtils.setField(service, "staleHours", 24);
    }

    @Test
    void flagsGhostAndBlocking() {
        when(brokerCredentials.primaryTraderUserId()).thenReturn(Optional.of(TRADER));
        when(brokerTruthService.syncUser(TRADER)).thenReturn(BrokerPositionTruthSnapshot.empty(true));

        PortfolioPosition ghost = openPosition("NSE_SPIKE_DETECTION", "NSE:INFY", BigDecimal.ONE, BigDecimal.ZERO);
        when(positionRepository.findAllRealOpenPositions()).thenReturn(List.of(ghost));
        when(signalRepository.findActiveRunningSignals()).thenReturn(List.of());

        StrategyExecutionConfig cfg = new StrategyExecutionConfig();
        cfg.setMaxPositions(1);
        when(configRepository.findByUserIdIsNullAndStrategyKeyAndDeletedFalse("NSE_SPIKE_DETECTION"))
                .thenReturn(Optional.of(cfg));

        Map<String, Object> result = service.reconciliation();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertEquals(1, rows.size());
        assertEquals("GHOST,BLOCKING", rows.get(0).get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("ghostCount"));
        assertEquals(1, summary.get("blockingCount"));
    }

    @Test
    void clearGhostsDelegatesToAccounting() {
        when(brokerCredentials.primaryTraderUserId()).thenReturn(Optional.empty());
        when(positionRepository.findAllRealOpenPositions()).thenReturn(List.of());
        when(signalRepository.findActiveRunningSignals()).thenReturn(List.of());
        when(portfolioAccountingService.clearZeroPriceGhostPositions()).thenReturn(2);

        Map<String, Object> result = service.clearGhostPositions();
        assertEquals(2, result.get("clearedGhosts"));
        verify(portfolioAccountingService).clearZeroPriceGhostPositions();
    }

    private static PortfolioPosition openPosition(
            String strategyKey, String symbol, BigDecimal qty, BigDecimal avgPrice
    ) {
        PortfolioPosition p = new PortfolioPosition();
        p.setId(UUID.randomUUID());
        p.setUserId(TRADER);
        p.setStrategyKey(strategyKey);
        p.setSymbol(symbol);
        p.setQuantity(qty);
        p.setAvgPrice(avgPrice);
        p.setUpdatedAt(Instant.now().minus(48, ChronoUnit.HOURS));
        return p;
    }
}
