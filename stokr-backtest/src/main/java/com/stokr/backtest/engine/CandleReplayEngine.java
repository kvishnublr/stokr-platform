package com.stokr.backtest.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface CandleReplayEngine {

    void replay(List<CandleBar> candles, CandleVisitor visitor);

    record CandleBar(Instant ts, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
    }

    @FunctionalInterface
    interface CandleVisitor {
        void onBar(CandleBar bar);
    }
}
