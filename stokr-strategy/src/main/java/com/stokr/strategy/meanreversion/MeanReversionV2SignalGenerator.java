package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.engine.StrategyQualityGateService;
import com.stokr.strategy.runtime.SignalCooldownService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Mean reversion v2 — wider envelope / relaxed RSI gates ({@link MeanReversionParams#V2}).
 */
@Service
public class MeanReversionV2SignalGenerator extends AbstractMeanReversionSignalGenerator {

    public MeanReversionV2SignalGenerator(MarketDataQueryService marketDataQueryService) {
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
        return MeanReversionParams.V2;
    }
}
