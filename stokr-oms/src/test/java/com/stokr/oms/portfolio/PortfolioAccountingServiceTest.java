package com.stokr.oms.portfolio;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.PortfolioDailySummaryRepository;
import com.stokr.oms.repository.PortfolioPnlSnapshotRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioAccountingServiceTest {

    @Mock
    private OmsExecutionRepository executionRepository;
    @Mock
    private PortfolioPositionRepository positionRepository;
    @Mock
    private PortfolioPnlSnapshotRepository pnlSnapshotRepository;
    @Mock
    private PortfolioDailySummaryRepository dailySummaryRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PortfolioAccountingService service;

    @Test
    void rebuildSymbolMergesRawAndPrefixedSymbols() {
        UUID userId = UUID.randomUUID();
        OmsExecution entry = execution(order(userId, "AXISBANK", "BUY"), "1", "100");
        OmsExecution reconciliation = execution(order(userId, "NSE:AXISBANK", "SELL"), "1", "100");

        PortfolioPosition raw = position(userId, "AXISBANK", "1");
        PortfolioPosition prefixed = position(userId, "NSE:AXISBANK", "-1");

        when(executionRepository.findAllForUserAndSymbolsOrdered(eq(userId), any())).thenReturn(List.of(entry, reconciliation));
        when(positionRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of(raw, prefixed));
        when(positionRepository.findByUserIdAndSymbolAndDeletedFalse(userId, "AXISBANK")).thenReturn(Optional.of(raw));

        service.rebuildSymbol(userId, "NSE:AXISBANK", "BROKER_TRUTH_RECONCILIATION");

        ArgumentCaptor<PortfolioPosition> captor = ArgumentCaptor.forClass(PortfolioPosition.class);
        verify(positionRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        assertThat(raw.getQuantity()).isEqualByComparingTo("0.00000000");
        assertThat(raw.getStrategyKey()).isEqualTo("BROKER_TRUTH_RECONCILIATION");
        assertThat(prefixed.isDeleted()).isTrue();
    }

    private static OmsOrder order(UUID userId, String symbol, String side) {
        OmsOrder order = new OmsOrder();
        order.setUserId(userId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setOrderType("MARKET");
        order.setQuantity(BigDecimal.ONE);
        order.setExecutionMode(ExecutionMode.LIVE);
        return order;
    }

    private static OmsExecution execution(OmsOrder order, String qty, String price) {
        OmsExecution execution = new OmsExecution();
        execution.setOrder(order);
        execution.setFilledQty(new BigDecimal(qty));
        execution.setAvgPrice(new BigDecimal(price));
        return execution;
    }

    private static PortfolioPosition position(UUID userId, String symbol, String qty) {
        PortfolioPosition position = new PortfolioPosition();
        position.setUserId(userId);
        position.setSymbol(symbol);
        position.setQuantity(new BigDecimal(qty));
        position.setAvgPrice(BigDecimal.ZERO);
        position.setRealizedPnl(BigDecimal.ZERO);
        position.setUnrealizedPnl(BigDecimal.ZERO);
        return position;
    }
}
