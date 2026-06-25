package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * ADV Cash Flow Strategy.
 * Detects sustained net buying pressure via money-flow analysis across 5 candles.
 * Entry when ≥65% of volume-weighted price flow is positive, price above VWAP,
 * volume above avg, and sufficient ATR.
 */
@Slf4j
@Component
public class AdvCashFlowStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "ADV_CASH_FLOW";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 15) return null;

        // Time gate: 9:30–12:30 IST
        Integer hour   = context.extra("istHour",   Integer.class);
        Integer minute = context.extra("istMinute", Integer.class);
        if (hour == null) return null;
        int istMins = hour * 60 + (minute != null ? minute : 0);
        if (istMins < 9 * 60 + 30 || istMins > 12 * 60 + 30) return null;

        // Net money flow over last 5 candles
        double netFlow = 0, totalFlow = 0;
        for (int i = n - 5; i < n; i++) {
            Candle c = candles.get(i);
            double flow = c.close().subtract(c.open()).doubleValue() * c.volume();
            netFlow   += flow;
            totalFlow += Math.abs(flow);
        }
        if (totalFlow <= 0) return null;
        double buyRatio = (netFlow + totalFlow) / (2.0 * totalFlow); // maps [-1,1] → [0,1]
        if (buyRatio < 0.65) {
            log.debug("ADV_CASH: buyRatio {} < 0.65", buyRatio);
            return null;
        }

        Candle c0 = candles.get(n - 1);

        // Price above VWAP
        BigDecimal vwap = context.extra("vwap", BigDecimal.class);
        if (vwap == null || vwap.compareTo(BigDecimal.ZERO) == 0) return null;
        if (c0.close().compareTo(vwap) <= 0) {
            log.debug("ADV_CASH: close {} <= vwap {}", c0.close(), vwap);
            return null;
        }

        // Current candle bullish
        if (c0.close().compareTo(c0.open()) <= 0) return null;

        // Volume > 1.5× 10-period avg
        long avgVol = candles.subList(n - 10, n).stream().mapToLong(Candle::volume).sum() / 10;
        if (avgVol == 0 || c0.volume() < avgVol * 1.5) {
            log.debug("ADV_CASH: volume {} < 1.5x avg {}", c0.volume(), avgVol);
            return null;
        }

        // ATR check
        BigDecimal atr = context.extra("atr14", BigDecimal.class);
        if (atr == null && context.indicators() != null) atr = context.indicators().get("ATR14");
        if (atr != null) {
            double atrPct = atr.divide(c0.close(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
            if (atrPct < 0.05) return null; // too flat
        }

        BigDecimal sl     = params.getStopLossPrice(c0.close(), Signal.Side.BUY);
        BigDecimal target = params.getTargetPrice(c0.close(), Signal.Side.BUY);

        return new Signal(context.symbol(), Signal.Side.BUY, c0.close(), sl, target,
                0.72, String.format("ADV_CASH buyRatio=%.2f vol=%d/%d", buyRatio, c0.volume(), avgVol),
                0.4, 0.25);
    }
}
