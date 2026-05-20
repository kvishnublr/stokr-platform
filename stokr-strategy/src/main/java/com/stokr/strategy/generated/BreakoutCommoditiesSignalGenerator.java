package com.stokr.strategy.generated;

import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Auto-generated strategy template for: BREAKOUT_COMMODITIES
 * Generated on: 2026-05-20
 *
 * Asset class : COMMODITY
 * Segment     : MCX
 * Exchange    : MCX
 * Timeframe   : 10m
 *
 * TODO: Implement your strategy logic in the evaluate() method.
 *       This class is compiled normally — no dynamic loading occurs.
 *       Once you implement the logic, rebuild the project.
 *
 * @see com.stokr.strategy.generated.BaseGeneratedStrategy for helper utilities.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "BREAKOUT_COMMODITIES",
    assetClass   = "COMMODITY",
    segment      = "MCX",
    exchange     = "MCX",
    timeframe    = "10m"
)
public class BreakoutCommoditiesSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    @Override
    public String key() {
        return "BREAKOUT_COMMODITIES";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        log.debug("strategy.evaluate strategy=BREAKOUT_COMMODITIES symbol={}", symbol);

        // TODO: Step 1 — Load candle / OHLCV data
        // Example: List<Candle> candles = loadCandles(context, "BREAKOUT_COMMODITIES", 20);

        // TODO: Step 2 — Compute indicators
        // Example (equity breakout):
        //   double high20 = highestHigh(candles, 20);
        //   double rsi    = computeRsi(candles, 14);

        // TODO: Step 3 — Evaluate signal condition
        //   if (currentPrice > high20 && rsi > 55) {
        //       return bullishSignal(context, "Breakout confirmed", high20, rsi);
        //   }

        // TODO (Commodity / Futures): Read lot_size from StrategyUniverseSymbol
        //   For MCX Gold: lotSize = 1 (or 10 for GOLDM)
        //   For BANKNIFTY futures: lotSize = 15

        // TODO (Options): Resolve strike and expiry from universe group
        //   Use tradingSymbol from strategy_universe_symbols, e.g. BANKNIFTY26MAYFUT

        return new StrategySignal(SignalType.HOLD, symbol, null, null);
    }

    // ─────────────────────────────────────────────────────────────
    // Add private helper methods below
    // ─────────────────────────────────────────────────────────────

}
