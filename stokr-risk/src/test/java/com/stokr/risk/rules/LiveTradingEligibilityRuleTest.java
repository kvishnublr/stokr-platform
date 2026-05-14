package com.stokr.risk.rules;

import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.risk.model.LiveTraderEligibilityResult;
import com.stokr.risk.model.RiskContext;
import com.stokr.risk.model.RiskDecision;
import com.stokr.risk.service.LiveTradingTraderEligibilityService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveTradingEligibilityRuleTest {

    @Test
    void simulatedAlwaysPassesRegardlessOfGate() {
        LiveTradingTraderEligibilityService eligibility = mock(LiveTradingTraderEligibilityService.class);
        LiveTradingEligibilityRule rule = new LiveTradingEligibilityRule(eligibility);
        OmsOrder o = new OmsOrder();
        o.setExecutionMode(ExecutionMode.SIMULATED);
        RiskContext ctx = ctx(o);
        assertThat(rule.evaluate(ctx).allowed()).isTrue();
        verify(eligibility, never()).evaluateForLiveOrder(any(), any(), any());
    }

    @Test
    void liveRejectedWhenGateClosed() {
        LiveTradingTraderEligibilityService eligibility = mock(LiveTradingTraderEligibilityService.class);
        when(eligibility.evaluateForLiveOrder(any(), any(), any())).thenReturn(
                LiveTraderEligibilityResult.reject(
                        "LIVE_TRADING_BLOCKED",
                        "Live trading disabled or platform not armed"
                )
        );
        LiveTradingEligibilityRule rule = new LiveTradingEligibilityRule(eligibility);
        OmsOrder o = new OmsOrder();
        o.setExecutionMode(ExecutionMode.LIVE);
        RiskDecision d = rule.evaluate(ctx(o));
        assertThat(d.allowed()).isFalse();
    }

    private static RiskContext ctx(OmsOrder o) {
        return RiskContext.forTests(UUID.randomUUID(), o, BigDecimal.ZERO, 0, LocalTime.NOON, ZoneId.of("UTC"), 0L);
    }
}
