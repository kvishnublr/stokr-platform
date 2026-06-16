package com.stokr.strategy.service;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SignalQualityGateServiceTest {

    private final SignalQualityGateService service = new SignalQualityGateService();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "minConfidence", 0.55d);
        ReflectionTestUtils.setField(service, "minRiskReward", 1.2d);
    }

    @Test
    void exitSignalsBypassQualityGateConfidenceChecks() {
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setSignalType(SignalType.EXIT);
        signal.setStrategyName("EMERGENCY_EXIT");
        signal.setSymbol("NSE:INFY");

        assertThat(service.dropReason(signal)).isNull();
    }

    @Test
    void productionSignalsStillNeedConfidence() {
        StrategySignalEntity signal = new StrategySignalEntity();
        signal.setSignalType(SignalType.BUY);
        signal.setStrategyName("INDEX_HUNT");
        signal.setSymbol("NSE:INFY");
        signal.setConfidenceScore(null);
        signal.setEntryReferencePrice(new BigDecimal("100"));
        signal.setTargetPrice(new BigDecimal("120"));
        signal.setStopPrice(new BigDecimal("95"));

        assertThat(service.dropReason(signal)).isEqualTo("Confidence missing");
    }
}
