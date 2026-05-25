package com.stokr.strategy.ematrend;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.engine.StrategyQualityGateService;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.runtime.SignalCooldownService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * EMA trend following with 4 accuracy filters:
 * 1. EMA21 slope: must be rising (BUY) or falling (SELL) — no flat-market crosses
 * 2. Strong close: bar closes above EMA9 (BUY) or below EMA9 (SELL)
 * 3. ATR-based SL (1.5x ATR) — wider than EMA21, survives normal pullbacks
 * 4. RSI momentum: BUY needs RSI > 50, SELL needs RSI < 50
 *
 * DISABLED: Losing signals (-7.81 on 11 signals)
 * Reason: EMA crossovers too noisy in sideways markets
 */
//@Service  // DISABLED
@RequiredArgsConstructor
public class EmaTrendFollowingSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataQueryService marketDataQueryService;
    private final SignalCooldownService signalCooldownService;
    private final StrategyQualityGateService qualityGateService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.ema.fast:9}")
    private int fast;

    @Value("${stokr.ema.slow:21}")
    private int slow;

    @Value("${stokr.ema.sl-atr-mult:1.5}")
    private double slAtrMult;

    @Value("${stokr.ema.target-atr-mult:2.5}")
    private double targetAtrMult;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        // Signal cooldown check — prevent spam (EMA can fire multiple times on trend changes)
        if (!signalCooldownService.shouldEmitSignal(symbol, StrategyKeys.EMA_TREND_FOLLOW, barOpenTime)) {
            return null; // still in cooldown for this symbol+strategy
        }

        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 400, barOpenTime);
        if (bars.size() < slow + 10) return null;

        ZonedDateTime z = barOpenTime.atZone(zone);
        LocalTime lt = z.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) return null;

        List<BigDecimal> closes = new ArrayList<>();
        for (MarketdataCandle c : bars) closes.add(c.getClosePrice());

        int n = closes.size();
        BigDecimal emaFast   = emaAt(closes, fast, n - 1);
        BigDecimal emaSlow   = emaAt(closes, slow, n - 1);
        BigDecimal prevFast  = emaAt(closes, fast, n - 2);
        BigDecimal prevSlow  = emaAt(closes, slow, n - 2);
        // EMA21 value 5 bars ago — for slope check
        BigDecimal slowPast  = emaAt(closes, slow, n - 6);

        if (emaFast == null || emaSlow == null || prevFast == null || prevSlow == null || slowPast == null) return null;

        boolean goldenCross = emaFast.compareTo(emaSlow) > 0 && prevFast.compareTo(prevSlow) <= 0;
        boolean deathCross  = emaFast.compareTo(emaSlow) < 0 && prevFast.compareTo(prevSlow) >= 0;

        if (!goldenCross && !deathCross) return null;

        // ── Filter 1: EMA21 slope — must be trending, not flat ──────────────────
        // EMA21 must have moved at least 0.05% over the last 5 bars
        double slopePct = emaSlow.subtract(slowPast).divide(slowPast, MC).doubleValue();
        if (goldenCross && slopePct < 0.0005) return null;   // EMA21 not rising enough
        if (deathCross  && slopePct > -0.0005) return null;  // EMA21 not falling enough

        MarketdataCandle last = bars.getLast();

        // ── Filter 2: Strong close — price on right side of EMA9 ────────────────
        if (goldenCross && last.getClosePrice().compareTo(emaFast) < 0) return null;
        if (deathCross  && last.getClosePrice().compareTo(emaFast) > 0) return null;

        // ── ATR(14) ──────────────────────────────────────────────────────────────
        BigDecimal atr = atr14(bars, n - 1);
        if (atr == null || atr.signum() <= 0) return null;

        // ── Filter 3: RSI confirmation ────────────────────────────────────────────
        double rsi = rsi14(bars, n - 1);
        if (goldenCross && rsi < 50.0) return null;
        if (deathCross  && rsi > 50.0) return null;

        // ── Dynamic confidence ────────────────────────────────────────────────────
        double slopeStrength = Math.min(Math.abs(slopePct) / 0.005, 1.0);
        double rsiStrength   = goldenCross ? Math.min((rsi - 50.0) / 30.0, 1.0)
                                           : Math.min((50.0 - rsi) / 30.0, 1.0);
        BigDecimal confidence = new BigDecimal(0.61 + (slopeStrength + rsiStrength) * 0.095)
                .setScale(2, RoundingMode.HALF_UP).min(new BigDecimal("0.90"));

        BigDecimal slDist     = atr.multiply(BigDecimal.valueOf(slAtrMult), MC);
        BigDecimal targetDist = atr.multiply(BigDecimal.valueOf(targetAtrMult), MC);

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.EMA_TREND_FOLLOW);
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

        if (goldenCross) {
            sig.setSignalType(SignalType.BUY);
            sig.setReasonText(String.format("EMA golden cross. Slope=+%.3f%%. RSI=%.0f. ATR-SL.", slopePct * 100, rsi));
            sig.setStopPrice(last.getClosePrice().subtract(slDist).setScale(2, RoundingMode.HALF_UP));
            sig.setTargetPrice(last.getClosePrice().add(targetDist).setScale(2, RoundingMode.HALF_UP));
        } else {
            sig.setSignalType(SignalType.SELL);
            sig.setReasonText(String.format("EMA death cross. Slope=%.3f%%. RSI=%.0f. ATR-SL.", slopePct * 100, rsi));
            sig.setStopPrice(last.getClosePrice().add(slDist).setScale(2, RoundingMode.HALF_UP));
            sig.setTargetPrice(last.getClosePrice().subtract(targetDist).setScale(2, RoundingMode.HALF_UP));
        }

        // Apply institutional quality gate filters before persistence
        List<MarketdataCandle> barsFor14Atr = bars.size() >= 14 ? bars.subList(Math.max(0, bars.size() - 14), bars.size()) : bars;
        return qualityGateService.validateSignal(sig, last, barsFor14Atr, barOpenTime);
    }

    // ── ATR(14) ───────────────────────────────────────────────────────────────
    private static BigDecimal atr14(List<MarketdataCandle> bars, int idx) {
        if (idx < 15) return null;
        BigDecimal atr = BigDecimal.ZERO;
        for (int i = idx - 13; i <= idx; i++) {
            BigDecimal hl  = bars.get(i).getHighPrice().subtract(bars.get(i).getLowPrice()).abs();
            BigDecimal hcp = bars.get(i).getHighPrice().subtract(bars.get(i - 1).getClosePrice()).abs();
            BigDecimal lcp = bars.get(i).getLowPrice().subtract(bars.get(i - 1).getClosePrice()).abs();
            atr = atr.add(hl.max(hcp).max(lcp));
        }
        return atr.divide(BigDecimal.valueOf(14), MC);
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

    // ── EMA calculation ───────────────────────────────────────────────────────
    private static BigDecimal emaAt(List<BigDecimal> closes, int period, int idx) {
        if (idx < period - 1) return null;
        BigDecimal k   = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), MC);
        BigDecimal ema = sma(closes.subList(0, period));
        for (int i = period; i <= idx; i++) {
            ema = closes.get(i).subtract(ema).multiply(k).add(ema);
        }
        return ema;
    }

    private static BigDecimal sma(List<BigDecimal> xs) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal x : xs) s = s.add(x);
        return s.divide(BigDecimal.valueOf(xs.size()), MC);
    }
}
