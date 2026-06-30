package com.stokr.strategy;

import com.stokr.marketdata.Candle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * EOD Momentum Lock — catches institutional end-of-day rebalancing at 14:30–15:10 IST.
 *
 * Logic: Stocks trending all day with accelerating volume into close continue.
 * Institutions rebalance before 3:30 PM, creating persistent directional pressure.
 *
 *   BUY:  stock up >0.5% on day, price > VWAP, volume accelerating → trend continuation
 *   SELL: stock down >0.5% on day, price < VWAP, volume accelerating → trend continuation
 *
 * Target: 0.7% (small, only 30–45 min left)
 * SL:     0.5%
 * Trail:  activates at 0.4%, 0.25% distance — locks in fast before EOD
 */
@Slf4j
@Component
public class EodMomentumStrategy implements StrategyPlugin {

    @Override
    public String getStrategyType() { return "EOD_MOMENTUM"; }

    @Override
    public Signal evaluate(MarketContext context, StrategyParams params) {
        List<Candle> candles = context.candles();
        int n = candles.size();
        if (n < 60) return null;

        Candle latest = context.getLatestCandle();
        if (latest == null || latest.timestamp() == null) return null;

        // Entry window: 14:30–15:10 IST only
        int hour = latest.timestamp().getHour();
        int min  = latest.timestamp().getMinute();
        int totalMin = hour * 60 + min;
        if (totalMin < 14 * 60 + 30 || totalMin > 15 * 60 + 10) return null;

        BigDecimal close = latest.close();
        double px = close.doubleValue();
        if (px < 50 || px > 5000) return null;

        // Compute intraday VWAP and today's open from all today's candles
        LocalDate today = latest.timestamp().toLocalDate();
        BigDecimal sumPV = BigDecimal.ZERO;
        long totalVol = 0;
        BigDecimal dayOpen = null;
        int todayCount = 0;

        for (Candle c : candles) {
            if (c.timestamp() == null) continue;
            if (!c.timestamp().toLocalDate().equals(today)) continue;
            int t = c.timestamp().getHour() * 60 + c.timestamp().getMinute();
            if (t < 9 * 60 + 15) continue;
            if (dayOpen == null) dayOpen = c.open();
            BigDecimal tp = c.high().add(c.low()).add(c.close())
                .divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            sumPV = sumPV.add(tp.multiply(BigDecimal.valueOf(c.volume())));
            totalVol += c.volume();
            todayCount++;
        }

        if (dayOpen == null || totalVol == 0 || todayCount < 50) return null;
        BigDecimal vwap = sumPV.divide(BigDecimal.valueOf(totalVol), 4, RoundingMode.HALF_UP);

        // Day return from 9:15 open
        double dayOpen0 = dayOpen.doubleValue();
        double dayReturnPct = (px - dayOpen0) / dayOpen0 * 100.0;

        // Volume acceleration: last 5 candles vs prior 5 candles
        if (n < 12) return null;
        long last5vol = 0, prev5vol = 0;
        for (int k = n - 5; k < n; k++) last5vol += candles.get(k).volume();
        for (int k = n - 10; k < n - 5; k++) prev5vol += candles.get(k).volume();
        if (prev5vol == 0) return null;
        double volRatio = (double) last5vol / prev5vol;
        if (volRatio < 1.6) return null; // volume must be strongly accelerating into close

        // Only stocks with a STRONG daily trend — avoids noisy sideways stocks
        // 1.5% threshold: roughly top 10% of trending stocks on any given day
        if (Math.abs(dayReturnPct) < 1.5) return null;

        boolean trendUp   = dayReturnPct > 0 && close.compareTo(vwap) > 0;
        boolean trendDown = dayReturnPct < 0 && close.compareTo(vwap) < 0;

        if (trendUp) {
            BigDecimal sl     = close.multiply(BigDecimal.valueOf(0.995)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = close.multiply(BigDecimal.valueOf(1.007)).setScale(2, RoundingMode.HALF_UP);
            double risk   = close.subtract(sl).doubleValue();
            double reward = target.subtract(close).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 1.0) return null;

            return new Signal(context.symbol(), Signal.Side.BUY, close, sl, target, 0.68,
                "EOD_MOM BUY day=" + String.format("+%.2f%%", dayReturnPct)
                    + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                    + " volRatio=" + String.format("%.1fx", volRatio)
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.4, 0.25);
        }

        if (trendDown) {
            BigDecimal sl     = close.multiply(BigDecimal.valueOf(1.005)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = close.multiply(BigDecimal.valueOf(0.993)).setScale(2, RoundingMode.HALF_UP);
            double risk   = sl.subtract(close).doubleValue();
            double reward = close.subtract(target).doubleValue();
            double rr     = risk > 0 ? reward / risk : 0;
            if (rr < 1.0) return null;

            return new Signal(context.symbol(), Signal.Side.SELL, close, sl, target, 0.68,
                "EOD_MOM SELL day=" + String.format("%.2f%%", dayReturnPct)
                    + " vwap=" + vwap.setScale(2, RoundingMode.HALF_UP)
                    + " volRatio=" + String.format("%.1fx", volRatio)
                    + " @" + close.setScale(2, RoundingMode.HALF_UP)
                    + " tgt=" + target + " rr=" + String.format("%.1f", rr),
                0.4, 0.25);
        }

        return null;
    }
}
