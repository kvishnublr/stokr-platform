package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Cash Ignition Strategy (ORB Breakout)
 * 
 * High probability intraday setup catching explosive volume at opening range breakout.
 * Focuses on quick entry and trailing SL for 10-15% potential on momentum bursts.
 */
@Slf4j
@Component
public class CashIgnitionStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() {
        return "CASH_IGNITION";
    }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 5) return null; // Need a few candles at least

        Candle latest = context.getLatestCandle();
        if (latest == null || latest.volume() <= 0) return null;
        
        double close = latest.close().doubleValue();
        if (close < 50) return null; // Filter out penny stocks

        // We need orbHigh and orbLow from context, assuming MarketContext populates them
        // like in MorningSurgeReversalStrategy
        BigDecimal orbHigh = context.extra("orbHigh", BigDecimal.class);
        BigDecimal orbLow = context.extra("orbLow", BigDecimal.class);
        
        if (orbHigh == null || orbLow == null) {
            // Fallback: Calculate roughly from first 15 mins if not provided by context
            return null;
        }

        // Time window: usually 9:30 to 10:30 IST
        Integer istHour = context.extra("istHour", Integer.class);
        Integer istMinute = context.extra("istMinute", Integer.class);
        if (istHour != null && istMinute != null) {
            int min = istHour * 60 + istMinute;
            if (min < 9 * 60 + 30 || min > 11 * 60) return null; // Only morning session
        }

        Candle prev = context.getPreviousCandle();
        if (prev == null) return null;

        // Condition 1: Breakout above ORB High
        boolean breakout = close > orbHigh.doubleValue() && prev.close().doubleValue() <= orbHigh.doubleValue();
        if (!breakout) return null;

        // Condition 2: High Volume (at least 2.5x of previous 10 candles avg)
        long volSum = 0;
        int volLen = Math.min(10, n - 1);
        for (int k = n - 1 - volLen; k < n - 1; k++) {
            volSum += candles.get(k).volume();
        }
        double avgVol = volLen > 0 ? (double) volSum / volLen : 1;
        if (avgVol == 0 || latest.volume() < avgVol * params.ignitionVolumeMultiplier()) return null;

        // Liquidity check: Minimum 1 Crore daily turnover on average
        if (close * avgVol < 10000000) return null;

        // Calculate SL and Target
        // SL is just below the breakout candle's low or ORB low
        double sl = Math.max(latest.low().doubleValue() * 0.995, orbLow.doubleValue());
        
        // Ensure SL is not too far (max risk)
        double riskPct = (close - sl) / close;
        if (riskPct > params.ignitionMaxSlPct() / 100.0) {
            sl = close * (1.0 - (params.ignitionMaxSlPct() / 100.0));
        }

        // Target (e.g. 1:3 Risk Reward or explosive target)
        double risk = close - sl;
        double target = close + (risk * 3.0); // 1:3 RR

        int score = 85; // High confidence if breaking out with volume
        
        BigDecimal entryBD = BigDecimal.valueOf(close).setScale(2, RoundingMode.HALF_UP);
        BigDecimal slBD = BigDecimal.valueOf(sl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tgtBD = BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP);

        log.info(String.format("CASH_IGNITION %s score=%d/100 @%s sl=%s tgt=%s",
            context.symbol(), score, entryBD, slBD, tgtBD));

        return new Signal(
            context.symbol(), Signal.Side.BUY, entryBD, slBD, tgtBD,
            score / 100.0,
            "CASH IGNITION ORB BREAK score=" + score + "/100 @" + entryBD + " tgt=" + tgtBD,
            params.ignitionTrailTriggerPct(), params.ignitionTrailDistancePct());
    }
}
