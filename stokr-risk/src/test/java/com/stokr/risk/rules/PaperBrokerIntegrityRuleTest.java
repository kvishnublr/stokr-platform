package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.risk.model.RiskContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperBrokerIntegrityRuleTest {

    private final PaperBrokerIntegrityRule rule = new PaperBrokerIntegrityRule();

    @Test
    void liveIgnoresPaperIntegrity() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.LIVE);
        o.setBrokerVendor("ZERODHA");
        assertThat(rule.evaluate(ctx(o)).allowed()).isTrue();
    }

    @Test
    void paperWithSimBrokerPasses() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.PAPER);
        o.setBrokerVendor("SIM");
        assertThat(rule.evaluate(ctx(o)).allowed()).isTrue();
    }

    @Test
    void paperRejectsLiveBrokerVendor() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.PAPER);
        o.setBrokerVendor("ZERODHA");
        assertThat(rule.evaluate(ctx(o)).allowed()).isFalse();
    }

    @Test
    void paperRejectsBlankBroker() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.PAPER);
        o.setBrokerVendor("  ");
        assertThat(rule.evaluate(ctx(o)).allowed()).isFalse();
    }

    private static OmsOrder baseOrder() {
        OmsOrder o = new OmsOrder();
        o.setUserId(UUID.randomUUID());
        o.setSymbol("NIFTY");
        o.setSide("BUY");
        o.setOrderType("MARKET");
        o.setQuantity(BigDecimal.ONE);
        return o;
    }

    private static RiskContext ctx(OmsOrder o) {
        return RiskContext.forTests(o.getUserId(), o, BigDecimal.ZERO, 0, LocalTime.NOON, ZoneId.of("UTC"), 0L);
    }
}
