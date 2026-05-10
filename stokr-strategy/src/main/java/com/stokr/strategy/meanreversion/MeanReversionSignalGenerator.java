package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.service.MarketDataQueryService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Default range-fade mean reversion ({@link MeanReversionParams#V1}).
 */
@Service
@Primary
public class MeanReversionSignalGenerator extends AbstractMeanReversionSignalGenerator {

    public MeanReversionSignalGenerator(MarketDataQueryService marketDataQueryService) {
        super(marketDataQueryService);
    }

    @Override
    protected MeanReversionParams variant() {
        return MeanReversionParams.V1;
    }
}
