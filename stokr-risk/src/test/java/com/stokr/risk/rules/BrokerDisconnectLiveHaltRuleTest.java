package com.stokr.risk.rules;

import com.stokr.common.simulation.SimulationModeService;
import com.stokr.oms.domain.ExecutionMode;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.risk.model.RiskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class BrokerDisconnectLiveHaltRuleTest {

    @Mock
    private SimulationModeService simulationModeService;

    private BrokerDisconnectLiveHaltRule rule;

    @BeforeEach
    void setUp() {
        rule = new BrokerDisconnectLiveHaltRule(simulationModeService);
    }

    @Test
    void simulatedIgnoresCircuit() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.SIMULATED);
        assertThat(rule.evaluate(ctx(o, false)).allowed()).isTrue();
        assertThat(rule.evaluate(ctx(o, true)).allowed()).isTrue();
    }

    @Test
    void liveAllowedWhenCircuitOpenFalse() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.LIVE);
        assertThat(rule.evaluate(ctx(o, false)).allowed()).isTrue();
    }

    @Test
    void liveRejectedWhenCircuitHalt() {
        OmsOrder o = baseOrder();
        o.setExecutionMode(ExecutionMode.LIVE);
        assertThat(rule.evaluate(ctx(o, true)).allowed()).isFalse();
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

    private static RiskContext ctx(OmsOrder o, boolean brokerHalt) {
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
                brokerHalt,
                BigDecimal.ZERO,
                null,
                null
        );
    }
}
