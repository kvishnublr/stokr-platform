package com.stokr.strategy.momentum;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Momentum breakout with 4 accuracy filters:
 * 1. Tight compression: 20-bar range < 2% of price (coiled spring)
 * 2. Strong close: bar closes in top/bottom 35% of its range
 * 3. ATR-based SL (1.5x ATR) — resists wick hunting
 * 4. RSI confirmation: BUY needs RSI > 52, SELL needs RSI < 48
 */
@Service
@RequiredArgsConstructor
public class MomentumBreakoutSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.momentum.lookback:20}")
    private int lookback;

    @Value("${stokr.momentum.compression-pct:0.02}")
    private double compressionPct;

    @Value("${stokr.momentum.rsi-bull:52}")
    private double rsiBull;

    @Value("${stokr.momentum.rsi-bear:48}")
    private double rsiBear;

    @Value("${stokr.momentum.sl-atr-mult:1.5}")
    private double slAtrMult;

    @Value("${stokr.momentum.target-atr-mult:3.0}")
    private double targetAtrMult;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 320, barOpenTime);
        if (bars.size() < lookback + 5) return null;

        ZonedDateTime z = barOpenTime.atZone(zone);
        LocalTime lt = z.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) return null;

        MarketdataCandle last = bars.getLast();
        MarketdataCandle prev = bars.get(bars.size() - 2);
        int from = Math.max(0, bars.size() - 1 - lookback);
        List<MarketdataCandle> window = bars.subList(from, bars.size() - 1);

        BigDecimal priorHigh = window.stream().map(MarketdataCandle::getHighPrice).max(BigDecimal::compareTo).orElse(null);
        BigDecimal priorLow  = window.stream().map(MarketdataCandle::getLowPrice).min(BigDecimal::compareTo).orElse(null);
        if (priorHigh == null || priorLow == null) return null;

        // ── Filter 1: Compression — range must be tight (coiled spring) ──────────
        BigDecimal rangeSize = priorHigh.subtract(priorLow);
        BigDecimal midPrice  = priorHigh.add(priorLow).divide(new BigDecimal("2"), MC);
        if (midPrice.signum() <= 0) return null;
        double rangePct = rangeSize.divide(midPrice, MC).doubleValue();
        if (rangePct > compressionPct) return null;   // range too wide — skip

        boolean bullBreakout = last.getClosePrice().compareTo(priorHigh) > 0
                && prev.getClosePrice().compareTo(priorHigh) <= 0;
        boolean bearBreakout = last.getClosePrice().compareTo(priorLow) < 0
                && prev.getClosePrice().compareTo(priorLow) >= 0;

        if (!bullBreakout && !bearBreakout) return null;

        // ── Filter 2: Strong close — bar must close decisively ───────────────────
        BigDecimal barRange = last.getHighPrice().subtract(last.getLowPrice());
        if (barRange.signum() > 0) {
            BigDecimal closePos = last.getClosePrice().subtract(last.getLowPrice()).divide(barRange, MC);
            if (bullBreakout && closePos.doubleValue() < 0.35) return null;  // close in bottom 65% — weak bull bar
            if (bearBreakout && closePos.doubleValue() > 0.65) return null;  // close in top 65% — weak bear bar
        }

        // ── ATR (14-period) ──────────────────────────────────────────────────────
        BigDecimal atr = atr14(bars, bars.size() - 1);
        if (atr == null || atr.signum() <= 0) return null;

        // ── Filter 3: RSI confirmation ────────────────────────────────────────────
        double rsi = rsi14(bars, bars.size() - 1);
        if (bullBreakout && rsi < rsiBull) return null;
        if (bearBreakout && rsi > rsiBear) return null;

        // ── Dynamic confidence based on RSI extremity ────────────────────────────
        double rsiStrength = bullBreakout ? Math.min((rsi - rsiBull) / 30.0, 1.0)
                                          : Math.min((rsiBear - rsi) / 30.0, 1.0);
        BigDecimal confidence = new BigDecimal(0.64 + rsiStrength * 0.14).setScale(2, RoundingMode.HALF_UP);

        // ── Build signal ─────────────────────────────────────────────────────────
        BigDecimal slDistance     = atr.multiply(BigDecimal.valueOf(slAtrMult), MC);
        BigDecimal targetDistance = atr.multiply(BigDecimal.valueOf(targetAtrMult), MC);

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.MOMENTUM_BREAKOUT);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSuggestedQty(BigDecimal.ONE);
        sig.setEntryReferencePrice(last.getClosePrice());
        sig.setConfidenceScore(confidence);
        sig.setAtrValue(atr.setScale(4, RoundingMode.HALF_UP));

        if (bullBreakout) {
            sig.setSignalType(SignalType.BUY);
            sig.setReasonText(String.format("Momentum breakout from %.1f%% compression. RSI=%.0f. ATR-SL.", rangePct * 100, rsi));
            sig.setStopPrice(last.getClosePrice().subtract(slDistance).setScale(2, RoundingMode.HALF_UP));
            sig.setTargetPrice(last.getClosePrice().add(targetDistance).setScale(2, RoundingMode.HALF_UP));
        } else {
            sig.setSignalType(SignalType.SELL);
            sig.setReasonText(String.format("Momentum breakdown from %.1f%% compression. RSI=%.0f. ATR-SL.", rangePct * 100, rsi));
            sig.setStopPrice(last.getClosePrice().add(slDistance).setScale(2, RoundingMode.HALF_UP));
            sig.setTargetPrice(last.getClosePrice().subtract(targetDistance).setScale(2, RoundingMode.HALF_UP));
        }
        return sig;
    }

    // ── ATR(14) using Wilder smoothing ────────────────────────────────────────
    private static BigDecimal atr14(List<MarketdataCandle> bars, int idx) {
        if (idx < 15) return null;
        BigDecimal atr = BigDecimal.ZERO;
        for (int i = idx - 13; i <= idx; i++) {
            atr = atr.add(tr(bars.get(i), bars.get(i - 1)));
        }
        atr = atr.divide(BigDecimal.valueOf(14), MC);
        return atr;
    }

    private static BigDecimal tr(MarketdataCandle c, MarketdataCandle prev) {
        BigDecimal hl  = c.getHighPrice().subtract(c.getLowPrice()).abs();
        BigDecimal hcp = c.getHighPrice().subtract(prev.getClosePrice()).abs();
        BigDecimal lcp = c.getLowPrice().subtract(prev.getClosePrice()).abs();
        return hl.max(hcp).max(lcp);
    }

    // ── RSI(14) ───────────────────────────────────────────────────────────────
    private static double rsi14(List<MarketdataCandle> bars, int idx) {
        if (idx < 15) return 50.0;
        double gain = 0, loss = 0;
        for (int i = idx - 13; i <= idx; i++) {
            double delta = bars.get(i).getClosePrice().subtract(bars.get(i - 1).getClosePrice()).doubleValue();
            if (delta > 0) gain += delta; else loss -= delta;
        }
        if (loss == 0) return 100.0;
        double rs = (gain / 14.0) / (loss / 14.0);
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
