package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.service.MarketDataQueryService;
import org.springframework.stereotype.Service;

/**
 * Mean reversion v2 — wider envelope / relaxed RSI gates ({@link MeanReversionParams#V2}).
 */
@Service
public class MeanReversionV2SignalGenerator extends AbstractMeanReversionSignalGenerator {

    public MeanReversionV2SignalGenerator(MarketDataQueryService marketDataQueryService) {
        super(marketDataQueryService);
    }

    @Override
    protected MeanReversionParams variant() {
        return MeanReversionParams.V2;
    }
}
