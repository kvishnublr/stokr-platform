package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.context.StrategyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads OHLCV windows for strategy evaluation. Uses {@code StrategyContext.asOf()} when set
 * (replay/backtest) so bars are point-in-time correct; otherwise uses latest bars (live scan).
 */
@Service
@RequiredArgsConstructor
public class StrategyCandleLoader {

    private final MarketDataQueryService marketDataQueryService;

    public List<MarketdataCandle> bars(StrategyContext context, String timeframe, int maxBars) {
        String symbol = context.symbol();
        if (context.asOf() != null) {
            return marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, maxBars, context.asOf());
        }
        return marketDataQueryService.lastBarsAsc(symbol, timeframe, maxBars);
    }
}
