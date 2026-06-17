package com.stokr.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pre-Open Trade Book Strategy (Strategy #3).
 * Evaluates pre-market conditions at 9:09 AM.
 * Conditions (all must be true for LONG):
 *   1. unfilled ratio > 0.35
 *   2. gap between 0.2% and 1.0%
 *   3. VIX < 25
 */
@Slf4j
@Component
public class PreOpenTradeBookStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "PRE_OPEN";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        BigDecimal currentPrice = context.currentPrice();
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) return null;

        // 1. Unfilled ratio > 0.35
        BigDecimal unfilledRatio = context.extra("unfilledRatio", BigDecimal.class);
        if (unfilledRatio == null) {
            // Try alternative key names from Chartink payload
            unfilledRatio = context.extra("preOpenUnfilledRatio", BigDecimal.class);
        }
        if (unfilledRatio == null) {
            log.debug("PreOpen: unfilled ratio not available");
            return null;
        }
        if (unfilledRatio.doubleValue() <= 0.35) {
            log.debug("PreOpen: unfilled ratio {} <= 0.35", unfilledRatio);
            return null;
        }

        // 2. Gap between 0.2% and 1.0%
        BigDecimal gapPct = context.extra("gapPct", BigDecimal.class);
        if (gapPct == null) {
            // Calculate from open vs prevClose if available
            BigDecimal open = context.getOpenPrice();
            BigDecimal prevClose = context.extra("prevClose", BigDecimal.class);
            if (open != null && prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                gapPct = open.subtract(prevClose)
                        .divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }
        if (gapPct == null) {
            log.debug("PreOpen: gap% not available");
            return null;
        }
        double gap = Math.abs(gapPct.doubleValue());
        if (gap < 0.2 || gap > 1.0) {
            log.debug("PreOpen: gap% {} not in [0.2, 1.0]", gap);
            return null;
        }

        // 3. VIX < 25
        BigDecimal vix = context.extra("vix", BigDecimal.class);
        if (vix == null) vix = context.indicators() != null ? context.indicators().get("VIX") : null;
        if (vix != null && vix.doubleValue() >= 25) {
            log.debug("PreOpen: VIX {} >= 25", vix);
            return null;
        }

        // Determine side from gap direction
        Signal.Side side = gapPct.doubleValue() > 0 ? Signal.Side.BUY : Signal.Side.SELL;
        BigDecimal sl = params.getStopLossPrice(currentPrice, side);
        BigDecimal target = params.getTargetPrice(currentPrice, side);

        return new Signal(context.symbol(), side, currentPrice, sl, target,
                0.68, "PreOpen " + side + " unfilled=" + unfilledRatio.setScale(2, RoundingMode.HALF_UP)
                        + " gap=" + gapPct.setScale(2, RoundingMode.HALF_UP) + "%"
                        + (vix != null ? " vix=" + vix.setScale(1, RoundingMode.HALF_UP) : ""));
    }
}
