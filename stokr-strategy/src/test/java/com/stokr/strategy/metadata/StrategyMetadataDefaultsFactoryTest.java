package com.stokr.strategy.metadata;

import com.stokr.strategy.domain.StrategyDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class StrategyMetadataDefaultsFactoryTest {

    @Test
    void nseSpikeDetectionMetadataPassesValidator() {
        StrategyDefinition def = new StrategyDefinition();
        def.setStrategyKey("NSE_SPIKE_DETECTION");
        def.setDisplayName("NSE Spike Detection (1m)");
        def.setDescription("1m momentum spike strategy");
        def.setCategory("INTRADAY");
        def.setRiskLevel("HIGH");
        def.setDefaultTimeframe("1m");

        var dto = StrategyMetadataDefaultsFactory.synthesize(def);
        assertThatCode(() -> StrategyMetadataDocumentValidator.validateOrThrow(dto)).doesNotThrowAnyException();
    }
}
