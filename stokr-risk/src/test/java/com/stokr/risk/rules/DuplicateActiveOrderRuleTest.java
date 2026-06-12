package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.model.RiskContext;
import com.stokr.strategy.repository.StrategySignalRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuplicateActiveOrderRuleTest {

    @Test
    void allowsWhenNoDuplicate() {
        OmsOrderRepository repo = mock(OmsOrderRepository.class);
        StrategySignalRepository signalRepo = mock(StrategySignalRepository.class);
        when(repo.countActiveSameDirection(any(), any(), any(), any(), any(), any())).thenReturn(0L);
        DuplicateActiveOrderRule rule = new DuplicateActiveOrderRule(repo, signalRepo);
        RiskContext ctx = ctx(order());
        assertThat(rule.evaluate(ctx).allowed()).isTrue();
    }

    @Test
    void rejectsWhenDuplicateExists() {
        OmsOrderRepository repo = mock(OmsOrderRepository.class);
        StrategySignalRepository signalRepo = mock(StrategySignalRepository.class);
        OmsOrder o = order();
        when(repo.countActiveSameDirection(eq(o.getUserId()), eq("NIFTY"), eq("BUY"), eq(ExecutionMode.LIVE), eq(o.getId()), any()))
                .thenReturn(1L);
        DuplicateActiveOrderRule rule = new DuplicateActiveOrderRule(repo, signalRepo);
        assertThat(rule.evaluate(ctx(o)).allowed()).isFalse();
    }

    @Test
    void allowsTerminalFlattenDespiteDuplicate() {
        OmsOrderRepository repo = mock(OmsOrderRepository.class);
        StrategySignalRepository signalRepo = mock(StrategySignalRepository.class);
        OmsOrder o = order();
        o.setStrategyKey("TERMINAL_FLATTEN");
        when(repo.countActiveSameDirection(any(), any(), any(), any(), any(), any())).thenReturn(1L);
        DuplicateActiveOrderRule rule = new DuplicateActiveOrderRule(repo, signalRepo);
        assertThat(rule.evaluate(ctx(o)).allowed()).isTrue();
    }

    @Test
    void skipsBacktestOrders() {
        OmsOrderRepository repo = mock(OmsOrderRepository.class);
        StrategySignalRepository signalRepo = mock(StrategySignalRepository.class);
        OmsOrder o = order();
        o.setBacktestRunId(UUID.randomUUID());
        DuplicateActiveOrderRule rule = new DuplicateActiveOrderRule(repo, signalRepo);
        assertThat(rule.evaluate(ctx(o)).allowed()).isTrue();
    }

    private static OmsOrder order() {
        OmsOrder o = new OmsOrder();
        o.setId(UUID.randomUUID());
        o.setUserId(UUID.randomUUID());
        o.setSymbol("NIFTY");
        o.setSide("BUY");
        o.setOrderType("MARKET");
        o.setQuantity(BigDecimal.ONE);
        o.setExecutionMode(ExecutionMode.LIVE);
        return o;
    }

    private static RiskContext ctx(OmsOrder o) {
        return RiskContext.forTests(o.getUserId(), o, BigDecimal.ZERO, 0, LocalTime.NOON, ZoneId.of("UTC"), 0L);
    }
}
