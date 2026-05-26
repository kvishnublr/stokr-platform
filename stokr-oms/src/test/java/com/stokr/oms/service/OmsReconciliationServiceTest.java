package com.stokr.oms.service;

import com.stokr.oms.domain.OmsExecution;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.OmsExecutionRepository;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.oms.repository.PortfolioPositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OmsReconciliationServiceTest {

    @Mock
    private OmsOrderRepository orderRepository;
    @Mock
    private OmsExecutionRepository executionRepository;
    @Mock
    private PortfolioPositionRepository positionRepository;

    @InjectMocks
    private OmsReconciliationService service;

    @Test
    void reconcileUser_ignoresBacktestExecutionsWhenCheckingPortfolioParity() {
        UUID userId = UUID.randomUUID();
        UUID backtestRunId = UUID.randomUUID();

        OmsOrder liveOrder = order("INFY", false, null);
        OmsExecution liveExec = execution(liveOrder, "4");

        OmsOrder backtestOrder = order("INFY", false, backtestRunId);
        OmsExecution backtestExec = execution(backtestOrder, "99");

        PortfolioPosition pos = new PortfolioPosition();
        pos.setSymbol("NSE:INFY");
        pos.setQuantity(new BigDecimal("4"));

        when(executionRepository.findAllForUserOrdered(userId)).thenReturn(List.of(liveExec, backtestExec));
        when(positionRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of(pos));
        when(orderRepository.countByUserIdAndDeletedFalse(userId)).thenReturn(2L);

        OmsReconciliationService.ReconciliationReport report = service.reconcileUser(userId);

        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void reconcileUser_reportsPerSymbolMismatchWithNormalizedSymbols() {
        UUID userId = UUID.randomUUID();

        OmsOrder order = order("INFY", false, null);
        OmsExecution exec = execution(order, "4");

        PortfolioPosition pos = new PortfolioPosition();
        pos.setSymbol("INFY");
        pos.setQuantity(new BigDecimal("3"));

        when(executionRepository.findAllForUserOrdered(userId)).thenReturn(List.of(exec));
        when(positionRepository.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of(pos));
        when(orderRepository.countByUserIdAndDeletedFalse(userId)).thenReturn(1L);

        OmsReconciliationService.ReconciliationReport report = service.reconcileUser(userId);

        assertThat(report.warnings()).containsExactly("SYMBOL_QTY_MISMATCH:INFY");
    }

    private static OmsOrder order(String symbol, boolean testTrade, UUID backtestRunId) {
        OmsOrder order = new OmsOrder();
        order.setSymbol(symbol);
        order.setSide("BUY");
        order.setTestTrade(testTrade);
        order.setBacktestRunId(backtestRunId);
        return order;
    }

    private static OmsExecution execution(OmsOrder order, String qty) {
        OmsExecution exec = new OmsExecution();
        exec.setOrder(order);
        exec.setFilledQty(new BigDecimal(qty));
        return exec;
    }
}
