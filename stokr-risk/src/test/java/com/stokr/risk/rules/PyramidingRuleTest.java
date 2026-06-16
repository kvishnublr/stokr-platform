package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.risk.model.RiskContext;
import com.stokr.strategy.domain.StrategyExecutionConfig;
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

class PyramidingRuleTest {

    @Test
    void scopesOpenPositionCheckToExecutionMode() {
        OmsOrderRepository repo = mock(OmsOrderRepository.class);
        OmsOrder order = order();
        when(repo.countActiveSameDirection(
                eq(order.getUserId()),
                eq("NIFTY"),
                eq("BUY"),
                eq(ExecutionMode.LIVE),
                eq(order.getId()),
                any()
        )).thenReturn(0L);

        assertThat(new PyramidingRule(repo).evaluate(ctx(order)).allowed()).isTrue();
    }

    @Test
    void rejectsSameModeOpenOrderWhenPyramidingDisabled() {
        OmsOrderRepository repo = mock(OmsOrderRepository.class);
        OmsOrder order = order();
        when(repo.countActiveSameDirection(
                eq(order.getUserId()),
                eq("NIFTY"),
                eq("BUY"),
                eq(ExecutionMode.LIVE),
                eq(order.getId()),
                any()
        )).thenReturn(1L);

        assertThat(new PyramidingRule(repo).evaluate(ctx(order)).allowed()).isFalse();
    }

    private static OmsOrder order() {
        OmsOrder o = new OmsOrder();
        o.setId(UUID.randomUUID());
        o.setUserId(UUID.randomUUID());
        o.setStrategyKey("INDEX_HUNT");
        o.setSymbol("NIFTY");
        o.setSide("BUY");
        o.setOrderType("MARKET");
        o.setQuantity(BigDecimal.ONE);
        o.setExecutionMode(ExecutionMode.LIVE);
        return o;
    }

    private static RiskContext ctx(OmsOrder o) {
        StrategyExecutionConfig cfg = new StrategyExecutionConfig();
        cfg.setAllowPyramiding(false);
        return new RiskContext(
                o.getUserId(),
                o,
                BigDecimal.ZERO,
                0,
                LocalTime.NOON,
                ZoneId.of("UTC"),
                0L,
                0,
                null,
                false,
                BigDecimal.ZERO,
                cfg,
                null
        );
    }
}
