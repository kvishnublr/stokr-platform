package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * First 15-minute candle breakout strategy for NSE equity.
 *
 * Logic:
 *   - Load last 30 × 15m candles for the symbol.
 *   - Identify the first 15m candle of today (NSE open = 9:15 IST).
 *   - BUY  when live price breaks above the first candle's HIGH (with RSI < 75).
 *   - SELL when live price breaks below the first candle's LOW  (with RSI > 25).
 *   - HOLD while price stays inside the first-candle range.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "TEST_CASH",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "15m"
)
public class TestCashSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final ZoneId IST            = ZoneId.of("Asia/Kolkata");
    private static final int    CANDLES_TO_LOAD = 30;

    private final MarketDataQueryService marketDataQueryService;

    @Override
    public String key() {
        return "TEST_CASH";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String     symbol    = context.symbol();
        BigDecimal lastPrice = context.lastPrice();

        if (lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return hold(context);
        }

        List<MarketdataCandle> candles = marketDataQueryService.lastBarsAsc(symbol, "15m", CANDLES_TO_LOAD);
        if (candles.size() < 2) {
            log.debug("strategy=TEST_CASH symbol={} candles={} insufficient", symbol, candles.size());
            return hold(context);
        }

        // First 15m candle of today starts at NSE open: 9:15 AM IST
        Instant todayOpen = ZonedDateTime.now(IST)
                .toLocalDate().atTime(9, 15).atZone(IST).toInstant();

        MarketdataCandle firstCandle = null;
        for (MarketdataCandle c : candles) {
            if (!c.getOpenTime().isBefore(todayOpen)) {
                firstCandle = c;
                break;
            }
        }

        if (firstCandle == null) {
            log.debug("strategy=TEST_CASH symbol={} no_opening_candle_today", symbol);
            return hold(context);
        }

        double firstHigh = firstCandle.getHighPrice().doubleValue();
        double firstLow  = firstCandle.getLowPrice().doubleValue();
        double price     = lastPrice.doubleValue();

        double[] closes = candles.stream()
                .mapToDouble(c -> c.getClosePrice().doubleValue())
                .toArray();
        double rsiVal = closes.length > 14 ? rsi(closes, 14) : Double.NaN;

        log.debug("strategy=TEST_CASH symbol={} price={} high={} low={} rsi={}",
                symbol, price, firstHigh, firstLow, rsiVal);

        if (price > firstHigh && (Double.isNaN(rsiVal) || rsiVal < 75)) {
            return bullishSignal(context,
                    String.format("15m breakout above %.2f (rsi=%.1f)", firstHigh, rsiVal));
        }

        if (price < firstLow && (Double.isNaN(rsiVal) || rsiVal > 25)) {
            return bearishSignal(context,
                    String.format("15m breakdown below %.2f (rsi=%.1f)", firstLow, rsiVal));
        }

        return hold(context);
    }
}
