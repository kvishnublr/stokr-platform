package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.service.MarketDataQueryService;
import org.springframework.stereotype.Component;

/**
 * Stub implementation of MeanReversionSignalGenerator
 * Kept for bean injection compatibility (MEAN_REVERSION strategies removed)
 */
@Component
public class MeanReversionSignalGeneratorStub extends MeanReversionSignalGenerator {
    
    private static final MarketDataQueryService DUMMY_SERVICE = null;
    
    public MeanReversionSignalGeneratorStub() {
        super(DUMMY_SERVICE);
    }
}
