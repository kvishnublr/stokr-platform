package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.engine.StrategyQualityGateService;
import com.stokr.strategy.runtime.SignalCooldownService;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public void setQualityGateService(StrategyQualityGateService qualityGateService) {
        this.qualityGateService = qualityGateService;
    }

    @Autowired
    public void setSignalCooldownService(SignalCooldownService signalCooldownService) {
        this.signalCooldownService = signalCooldownService;
    }

    @Override
    protected MeanReversionParams variant() {
        return MeanReversionParams.V1;
    }
}
