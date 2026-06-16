package com.stokr.backtest.engine;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultCandleReplayEngine implements CandleReplayEngine {

    @Override
    public void replay(List<CandleBar> candles, CandleVisitor visitor) {
        for (CandleBar bar : candles) {
            visitor.onBar(bar);
        }
    }
}
