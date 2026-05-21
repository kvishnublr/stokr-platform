package com.stokr.risk.rules;

import com.stokr.oms.domain.OmsOrder;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MaxDailyLossRuleTest {

    @Test
    void disabledWhenLimitIsZero() {
        MaxDailyLossRule rule = new MaxDailyLossRule();
        ReflectionTestUtils.setField(rule, "maxDailyLossAmount", BigDecimal.ZERO);
        OmsOrder o = order();
        RiskContext ctx = ctx(o, new BigDecimal("-999999"));
        assertThat(rule.evaluate(ctx).allowed()).isTrue();
    }

    @Test
    void skipsBacktestOrders() {
        MaxDailyLossRule rule = new MaxDailyLossRule();
        ReflectionTestUtils.setField(rule, "maxDailyLossAmount", new BigDecimal("100"));
        OmsOrder o = order();
        o.setBacktestRunId(UUID.randomUUID());
        RiskContext ctx = ctx(o, new BigDecimal("-999999"));
        assertThat(rule.evaluate(ctx).allowed()).isTrue();
    }

    @Test
    void rejectsWhenDayPnlBelowFloor() {
        MaxDailyLossRule rule = new MaxDailyLossRule();
        ReflectionTestUtils.setField(rule, "maxDailyLossAmount", new BigDecimal("5000"));
        OmsOrder o = order();
        RiskContext ctx = ctx(o, new BigDecimal("-6000"));
        RiskDecision d = rule.evaluate(ctx);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reasonCode()).isEqualTo("MAX_DAILY_LOSS");
    }

    @Test
    void allowsWhenAboveFloor() {
        MaxDailyLossRule rule = new MaxDailyLossRule();
        ReflectionTestUtils.setField(rule, "maxDailyLossAmount", new BigDecimal("5000"));
        OmsOrder o = order();
        RiskContext ctx = ctx(o, new BigDecimal("-4000"));
        assertThat(rule.evaluate(ctx).allowed()).isTrue();
    }

    private static OmsOrder order() {
        OmsOrder o = new OmsOrder();
        o.setUserId(UUID.randomUUID());
        o.setSymbol("NIFTY");
        o.setSide("BUY");
        o.setOrderType("MARKET");
        o.setQuantity(BigDecimal.ONE);
        return o;
    }

    private static RiskContext ctx(OmsOrder o, BigDecimal dayPnl) {
        return new RiskContext(
                o.getUserId(),
                o,
                dayPnl,
                0,
                LocalTime.NOON,
                ZoneId.of("UTC"),
                0L,
                0,
                null,
                false,
                BigDecimal.ZERO,
                null,
                null
        );
    }
}
