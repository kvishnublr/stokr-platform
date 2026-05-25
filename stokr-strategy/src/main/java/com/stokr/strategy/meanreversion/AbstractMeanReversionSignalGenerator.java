package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.engine.StrategyQualityGateService;
import com.stokr.strategy.meanreversion.runtime.MeanReversionEvaluationEnvelope;
import com.stokr.strategy.meanreversion.runtime.MeanReversionReplayState;
import com.stokr.strategy.meanreversion.runtime.MeanReversionRuntimeParams;
import com.stokr.strategy.runtime.SignalCooldownService;
import com.stokr.strategy.signals.SignalType;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractMeanReversionSignalGenerator {

    protected final MarketDataQueryService marketDataQueryService;
    protected StrategyQualityGateService qualityGateService;
    protected SignalCooldownService signalCooldownService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.strategy.system-user-id:33333333-3333-3333-3333-333333333333}")
    private java.util.UUID systemUserId;

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    protected abstract MeanReversionParams variant();

    public StrategySignalEntity evaluatePersistable(String symbol, java.util.UUID userId, java.util.UUID backtestRunId, String pipeline) {
        List<MarketdataCandle> bars1m = marketDataQueryService.lastBarsAsc(symbol, "1m", 240);
        List<MarketdataCandle> bars5m = marketDataQueryService.lastBarsAsc(symbol, "5m", 120);
        ZonedDateTime evaluationZ = ZonedDateTime.now(zone);
        LocalDate vwapDay = evaluationZ.toLocalDate();
        Instant barOpenTime = evaluationZ.toInstant();
        return evaluateFromBars(symbol, bars1m, bars5m, userId, backtestRunId, pipeline, evaluationZ, vwapDay, null, barOpenTime);
    }

    /**
     * Deterministic evaluation at a historical candle open time (replay/backtests).
     */
    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            java.util.UUID userId,
            java.util.UUID backtestRunId,
            String pipeline,
            Instant barOpenTime
    ) {
        return evaluatePersistableAtOpen(symbol, userId, backtestRunId, pipeline, barOpenTime, "1m", null);
    }

    /**
     * @param primaryTimeframe candle series used for microstructure (e.g. {@code 1m} for range fade)
     * @param env              when non-null, {@link MeanReversionRuntimeParams} and session overrides apply; shared {@link MeanReversionReplayState} carries cooldown / confirmation across bars
     */
    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            java.util.UUID userId,
            java.util.UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String primaryTimeframe,
            MeanReversionEvaluationEnvelope env
    ) {
        String primary = primaryTimeframe != null && !primaryTimeframe.isBlank() ? primaryTimeframe : "1m";
        String higher = env != null && env.higherTimeframe() != null && !env.higherTimeframe().isBlank()
                ? env.higherTimeframe()
                : ("1m".equals(primary) ? "5m" : primary);
        List<MarketdataCandle> barsPrimary = marketDataQueryService.lastBarsAscEndingAt(symbol, primary, 240, barOpenTime);
        List<MarketdataCandle> barsHigher = marketDataQueryService.lastBarsAscEndingAt(symbol, higher, 120, barOpenTime);
        ZoneId z = env != null ? env.zone() : zone;
        ZonedDateTime evaluationZ = barOpenTime.atZone(z);
        LocalDate vwapDay = evaluationZ.toLocalDate();
        return evaluateFromBars(symbol, barsPrimary, barsHigher, userId, backtestRunId, pipeline, evaluationZ, vwapDay, env, barOpenTime);
    }

    private StrategySignalEntity evaluateFromBars(
            String symbol,
            List<MarketdataCandle> barsPrimary,
            List<MarketdataCandle> barsHigher,
            java.util.UUID userId,
            java.util.UUID backtestRunId,
            String pipeline,
            ZonedDateTime evaluationZ,
            LocalDate vwapDay,
            MeanReversionEvaluationEnvelope env,
            Instant barOpenTime
    ) {
        MeanReversionParams catalog = variant();
        MeanReversionRuntimeParams rp = env != null
                ? env.params()
                : MeanReversionRuntimeParams.fromVariant(catalog);
        MeanReversionReplayState st = env != null ? env.state() : new MeanReversionReplayState();
        int barIndex = env != null ? env.barIndex() : 0;

        ZoneId z = env != null ? env.zone() : zone;
        LocalTime sessStart = env != null ? env.sessionStart() : sessionStart;
        LocalTime sessEnd = env != null ? env.sessionEnd() : sessionEnd;

        st.beginBar(barIndex, rp.maxHoldingCandles());
        if (!st.canEmitSignal(barIndex, rp)) {
            return null;
        }

        int minBars = 60 + Math.max(0, rp.confirmationCandles());
        if (barsPrimary.size() < minBars || barsHigher.size() < 10) {
            return null;
        }

        LocalTime lt = evaluationZ.toLocalTime();
        if (lt.isBefore(sessStart) || lt.isAfter(sessEnd)) {
            return null;
        }

        MarketdataCandle last = barsPrimary.getLast();
        if (!candleOhlcFinite(last)) {
            return null;
        }

        List<BigDecimal> closes = closes(barsPrimary);

        BigDecimal rsi = WilderRsi.last(closes, 14);
        BigDecimal atr = WilderAtr.last(barsPrimary, 14);
        BigDecimal ema20 = Ema.last(closes, 20);

        SessionVwap vwap = SessionVwap.compute(barsPrimary, z, vwapDay);

        RangeBounds range = RangeBounds.lastN(barsPrimary, 20);

        MarketRegime regime = MarketRegimeDetector.detect(barsPrimary, barsHigher, atr, ema20, vwap.value(), range);

        BigDecimal widthPct = range.width().divide(last.getClosePrice(), MC).multiply(BigDecimal.valueOf(100), MC);

        BigDecimal atrCutoff = rp.atrCompressionCutoff();
        boolean atrCompressed = atr.compareTo(BigDecimal.ZERO) > 0
                && atr.divide(last.getClosePrice(), MC).compareTo(atrCutoff) < 0;

        boolean nearVwap = vwap.value().compareTo(BigDecimal.ZERO) > 0
                && last.getClosePrice().subtract(vwap.value()).abs()
                .divide(last.getClosePrice(), MC)
                .compareTo(new BigDecimal("0.003")) < 0;

        boolean emaFlat = ema20 != null && last.getClosePrice().subtract(ema20).abs()
                .divide(last.getClosePrice(), MC)
                .compareTo(new BigDecimal("0.0025")) < 0;

        BigDecimal effWidthCap = rp.effectiveEntryWidthCap(catalog);
        boolean sideways =
                regime == MarketRegime.SIDEWAYS
                        && atrCompressed
                        && emaFlat
                        && nearVwap
                        && widthPct.compareTo(effWidthCap) < 0;

        st.trackSidewaysRegime(sideways);
        if (!sideways) {
            return null;
        }
        if (!st.confirmationMet(rp.confirmationCandles())) {
            return null;
        }

        if (!volumeGateOk(barsPrimary, last, rp)) {
            return null;
        }

        BigDecimal spreadBps = rp.spreadToleranceBps() != null ? rp.spreadToleranceBps() : BigDecimal.ZERO;
        BigDecimal baseEps = last.getClosePrice().multiply(new BigDecimal("0.0005"), MC).max(new BigDecimal("0.5"));
        BigDecimal eps = baseEps.multiply(BigDecimal.ONE.add(spreadBps.movePointLeft(4), MC), MC);

        boolean touchLow = last.getLowPrice().subtract(range.low()).abs().compareTo(eps) <= 0;
        boolean touchHigh = last.getHighPrice().subtract(range.high()).abs().compareTo(eps) <= 0;

        boolean bullishRejection = last.getClosePrice().compareTo(last.getOpenPrice()) > 0
                && last.getOpenPrice().subtract(last.getLowPrice()).compareTo(last.getClosePrice().subtract(last.getOpenPrice()).multiply(BigDecimal.valueOf(2))) > 0;

        boolean bearishRejection = last.getClosePrice().compareTo(last.getOpenPrice()) < 0
                && last.getHighPrice().subtract(last.getOpenPrice()).compareTo(last.getOpenPrice().subtract(last.getLowPrice()).multiply(BigDecimal.valueOf(2))) > 0;

        boolean belowVwap = last.getClosePrice().compareTo(vwap.value()) < 0;
        boolean aboveVwap = last.getClosePrice().compareTo(vwap.value()) > 0;

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(catalog.catalogStrategyKey());
        sig.setStrategyVersion(catalog.strategyVersion());
        sig.setSymbol(symbol);
        sig.setUserId(userId != null ? userId : systemUserId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setRsiValue(rsi);
        sig.setAtrValue(atr);
        sig.setRangeHigh(range.high());
        sig.setRangeLow(range.low());
        sig.setMarketRegime(regime.name());
        sig.setVwapDistance(last.getClosePrice().subtract(vwap.value()).divide(last.getClosePrice(), MC));

        String indicatorSnap = "{\"rsi\":\"" + rsi.toPlainString() + "\",\"atr\":\"" + atr.toPlainString()
                + "\",\"rangeHigh\":\"" + range.high().toPlainString() + "\",\"rangeLow\":\"" + range.low().toPlainString()
                + "\",\"widthPct\":\"" + widthPct.toPlainString() + "\"}";
        sig.setParameterSnapshotJson(rp.toExecutionSnapshotJson(catalog));
        sig.setIndicatorSnapshotJson(indicatorSnap);

        BigDecimal conf = rp.scaledConfidence();

        if (touchLow && rsi.compareTo(rp.rsiBuyMax()) < 0 && bullishRejection && belowVwap) {
            sig.setSignalType(SignalType.BUY);
            sig.setConfidenceScore(conf);
            sig.setRejectionPattern("BULL_REJECTION");
            sig.setReasonText("Range-low fade long (" + catalog.catalogStrategyKey() + ")");
            BigDecimal structuralStop = last.getLowPrice().min(last.getOpenPrice());
            BigDecimal stop = resolveLongStop(last, atr, structuralStop, rp);
            sig.setStopPrice(stop);
            BigDecimal mid = range.mid();
            BigDecimal rrTarget = computeRrTarget(last.getClosePrice(), stop, mid, true, rp);
            sig.setTargetPrice(rrTarget);
            sig.setEntryReferencePrice(last.getClosePrice());
            sig.setSuggestedQty(BigDecimal.ONE);
            st.recordSignalEmitted(barIndex);
            // Apply institutional quality gate filters before persistence
            List<MarketdataCandle> barsFor14Atr = barsPrimary.size() >= 14 ? barsPrimary.subList(Math.max(0, barsPrimary.size() - 14), barsPrimary.size()) : barsPrimary;
            return qualityGateService != null ? qualityGateService.validateSignal(sig, last, barsFor14Atr, barOpenTime) : sig;
        }

        if (touchHigh && rsi.compareTo(rp.rsiSellMin()) > 0 && bearishRejection && aboveVwap) {
            sig.setSignalType(SignalType.SELL);
            sig.setConfidenceScore(conf);
            sig.setRejectionPattern("BEAR_REJECTION");
            sig.setReasonText("Range-high fade short (" + catalog.catalogStrategyKey() + ")");
            BigDecimal structuralStop = last.getHighPrice().max(last.getOpenPrice());
            BigDecimal stop = resolveShortStop(last, atr, structuralStop, rp);
            sig.setStopPrice(stop);
            BigDecimal mid = range.mid();
            BigDecimal rrTarget = computeRrTarget(last.getClosePrice(), stop, mid, false, rp);
            sig.setTargetPrice(rrTarget);
            sig.setEntryReferencePrice(last.getClosePrice());
            sig.setSuggestedQty(BigDecimal.ONE);
            st.recordSignalEmitted(barIndex);
            // Apply institutional quality gate filters before persistence
            List<MarketdataCandle> barsFor14Atr = barsPrimary.size() >= 14 ? barsPrimary.subList(Math.max(0, barsPrimary.size() - 14), barsPrimary.size()) : barsPrimary;
            return qualityGateService != null ? qualityGateService.validateSignal(sig, last, barsFor14Atr, barOpenTime) : sig;
        }

        return null;
    }

    private static boolean candleOhlcFinite(MarketdataCandle c) {
        return c.getOpenPrice() != null && c.getHighPrice() != null && c.getLowPrice() != null && c.getClosePrice() != null
                && c.getOpenPrice().signum() > 0 && c.getClosePrice().signum() > 0;
    }

    private static boolean volumeGateOk(List<MarketdataCandle> bars, MarketdataCandle last, MeanReversionRuntimeParams rp) {
        if (rp.volumeFilter() == com.stokr.strategy.meanreversion.runtime.VolumeFilterMode.OFF) {
            return true;
        }
        int n = Math.min(20, bars.size() - 1);
        if (n <= 0 || last.getVolume() == null) {
            return true;
        }
        int from = bars.size() - n;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = from; i < bars.size(); i++) {
            MarketdataCandle b = bars.get(i);
            if (b.getVolume() != null) {
                sum = sum.add(b.getVolume());
                count++;
            }
        }
        if (count == 0) {
            return true;
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(count), MC);
        if (avg.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        BigDecimal rel = last.getVolume().divide(avg, MC);
        return switch (rp.volumeFilter()) {
            case OFF -> true;
            case RELAXED -> rel.compareTo(rp.volumeRelaxedFloor()) >= 0;
            case STRICT -> rel.compareTo(rp.volumeStrictFloor()) >= 0;
        };
    }

    private static BigDecimal resolveLongStop(MarketdataCandle last, BigDecimal atr, BigDecimal structuralStop, MeanReversionRuntimeParams rp) {
        BigDecimal pctStop = last.getClosePrice().multiply(BigDecimal.ONE.subtract(rp.stopLossPercent().movePointLeft(2), MC), MC);
        if ("ATR_MULTIPLE".equalsIgnoreCase(rp.stopLossType()) && atr.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal mult = rp.stopLossPercent().max(new BigDecimal("0.5")).min(new BigDecimal("6"));
            BigDecimal atrStop = last.getClosePrice().subtract(atr.multiply(mult, MC), MC);
            return structuralStop.max(pctStop).max(atrStop);
        }
        return structuralStop.max(pctStop);
    }

    private static BigDecimal resolveShortStop(MarketdataCandle last, BigDecimal atr, BigDecimal structuralStop, MeanReversionRuntimeParams rp) {
        BigDecimal pctStop = last.getClosePrice().multiply(BigDecimal.ONE.add(rp.stopLossPercent().movePointLeft(2), MC), MC);
        if ("ATR_MULTIPLE".equalsIgnoreCase(rp.stopLossType()) && atr.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal mult = rp.stopLossPercent().max(new BigDecimal("0.5")).min(new BigDecimal("6"));
            BigDecimal atrStop = last.getClosePrice().add(atr.multiply(mult, MC), MC);
            return structuralStop.min(pctStop).min(atrStop);
        }
        return structuralStop.min(pctStop);
    }

    private static BigDecimal computeRrTarget(
            BigDecimal entry,
            BigDecimal stop,
            BigDecimal midpoint,
            boolean isLong,
            MeanReversionRuntimeParams rp
    ) {
        BigDecimal risk = entry.subtract(stop).abs();
        BigDecimal rrMult = rp.rrRiskMultiplier();
        BigDecimal rrMinTarget = isLong ? entry.add(risk.multiply(rrMult, MC)) : entry.subtract(risk.multiply(rrMult, MC), MC);
        BigDecimal tpPct = rp.takeProfitPercent().movePointLeft(2);
        BigDecimal tpCap = isLong
                ? entry.multiply(BigDecimal.ONE.add(tpPct, MC), MC)
                : entry.multiply(BigDecimal.ONE.subtract(tpPct, MC), MC);
        if (isLong) {
            BigDecimal best = midpoint.max(rrMinTarget).min(tpCap);
            if (rp.partialExitEnabled()) {
                best = entry.add(best.subtract(entry, MC).multiply(new BigDecimal("0.75"), MC), MC);
            }
            if (rp.trailingStopEnabled()) {
                best = best.multiply(new BigDecimal("1.01"), MC);
            }
            return best.min(entry.add(entry.multiply(new BigDecimal("0.02"), MC), MC));
        }
        BigDecimal best = midpoint.min(rrMinTarget).max(tpCap);
        if (rp.partialExitEnabled()) {
            best = entry.subtract(entry.subtract(best, MC).multiply(new BigDecimal("0.75"), MC), MC);
        }
        if (rp.trailingStopEnabled()) {
            best = best.multiply(new BigDecimal("0.99"), MC);
        }
        return best.max(entry.subtract(entry.multiply(new BigDecimal("0.02"), MC), MC));
    }

    private static List<BigDecimal> closes(List<MarketdataCandle> bars) {
        ArrayList<BigDecimal> out = new ArrayList<>(bars.size());
        for (MarketdataCandle c : bars) {
            out.add(c.getClosePrice());
        }
        return out;
    }

    private enum MarketRegime {
        SIDEWAYS,
        TRENDING,
        BREAKOUT
    }

    private static final class MarketRegimeDetector {
        static MarketRegime detect(
                List<MarketdataCandle> m1,
                List<MarketdataCandle> m5,
                BigDecimal atr,
                BigDecimal ema20,
                BigDecimal vwap,
                RangeBounds range
        ) {
            MarketdataCandle last1 = m1.getLast();
            BigDecimal breakoutHigh = range.high().add(range.width().multiply(new BigDecimal("0.25")));
            BigDecimal breakoutLow = range.low().subtract(range.width().multiply(new BigDecimal("0.25")));
            if (last1.getClosePrice().compareTo(breakoutHigh) > 0 || last1.getClosePrice().compareTo(breakoutLow) < 0) {
                return MarketRegime.BREAKOUT;
            }
            if (ema20 != null && atr.compareTo(BigDecimal.ZERO) > 0) {
                List<BigDecimal> allCloses = closes(m1);
                BigDecimal prevEma20 = Ema.at(allCloses, 20, m1.size() - 6);
                if (prevEma20 != null) {
                    BigDecimal slope = ema20.subtract(prevEma20);
                    if (slope.abs().divide(last1.getClosePrice(), MC).compareTo(new BigDecimal("0.004")) > 0) {
                        return MarketRegime.TRENDING;
                    }
                }
            }
            BigDecimal lastVwapDist = last1.getClosePrice().subtract(vwap).abs().divide(last1.getClosePrice(), MC);
            if (lastVwapDist.compareTo(new BigDecimal("0.007")) > 0) {
                return MarketRegime.TRENDING;
            }
            return MarketRegime.SIDEWAYS;
        }
    }

    private record RangeBounds(BigDecimal high, BigDecimal low) {
        BigDecimal mid() {
            return high.add(low).divide(BigDecimal.valueOf(2), MC);
        }

        BigDecimal width() {
            return high.subtract(low).abs();
        }

        static RangeBounds lastN(List<MarketdataCandle> bars, int n) {
            int from = Math.max(0, bars.size() - n);
            BigDecimal high = bars.subList(from, bars.size()).stream().map(MarketdataCandle::getHighPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal low = bars.subList(from, bars.size()).stream().map(MarketdataCandle::getLowPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            return new RangeBounds(high, low);
        }
    }

    private record SessionVwap(BigDecimal value) {
        static SessionVwap compute(List<MarketdataCandle> bars1m, ZoneId zone, LocalDate day) {
            BigDecimal pv = BigDecimal.ZERO;
            BigDecimal vol = BigDecimal.ZERO;
            ZonedDateTime open = day.atTime(LocalTime.of(9, 25)).atZone(zone);
            for (MarketdataCandle c : bars1m) {
                if (c.getOpenTime().isBefore(open.toInstant())) {
                    continue;
                }
                BigDecimal tp = c.getHighPrice().add(c.getLowPrice()).add(c.getClosePrice()).divide(BigDecimal.valueOf(3), MC);
                BigDecimal v = c.getVolume() != null ? c.getVolume() : BigDecimal.ONE;
                pv = pv.add(tp.multiply(v));
                vol = vol.add(v);
            }
            if (vol.compareTo(BigDecimal.ZERO) == 0) {
                return new SessionVwap(BigDecimal.ZERO);
            }
            return new SessionVwap(pv.divide(vol, MC));
        }
    }

    private static final class Ema {
        static BigDecimal last(List<BigDecimal> closes, int period) {
            return at(closes, period, closes.size() - 1);
        }

        static BigDecimal at(List<BigDecimal> closes, int period, int idx) {
            if (idx < period - 1) {
                return null;
            }
            BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), MC);
            BigDecimal ema = sma(closes.subList(0, period));
            for (int i = period; i <= idx; i++) {
                ema = closes.get(i).subtract(ema).multiply(k).add(ema);
            }
            return ema;
        }

        static BigDecimal sma(List<BigDecimal> xs) {
            BigDecimal s = BigDecimal.ZERO;
            for (BigDecimal x : xs) {
                s = s.add(x);
            }
            return s.divide(BigDecimal.valueOf(xs.size()), MC);
        }
    }

    private static final class WilderRsi {
        static BigDecimal last(List<BigDecimal> closes, int period) {
            if (closes.size() < period + 1) {
                return BigDecimal.valueOf(50);
            }
            List<BigDecimal> gains = new ArrayList<>();
            List<BigDecimal> losses = new ArrayList<>();
            for (int i = 1; i < closes.size(); i++) {
                BigDecimal ch = closes.get(i).subtract(closes.get(i - 1));
                gains.add(ch.compareTo(BigDecimal.ZERO) > 0 ? ch : BigDecimal.ZERO);
                losses.add(ch.compareTo(BigDecimal.ZERO) < 0 ? ch.abs() : BigDecimal.ZERO);
            }
            BigDecimal avgGain = Ema.sma(gains.subList(0, period));
            BigDecimal avgLoss = Ema.sma(losses.subList(0, period));
            for (int i = period; i < gains.size(); i++) {
                avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1)).add(gains.get(i)).divide(BigDecimal.valueOf(period), MC);
                avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1)).add(losses.get(i)).divide(BigDecimal.valueOf(period), MC);
            }
            if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.valueOf(100);
            }
            BigDecimal rs = avgGain.divide(avgLoss, MC);
            return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), MC));
        }
    }

    private static final class WilderAtr {
        static BigDecimal last(List<MarketdataCandle> bars, int period) {
            if (bars.size() < period + 1) {
                return BigDecimal.ZERO;
            }
            List<BigDecimal> trs = new ArrayList<>();
            for (int i = 1; i < bars.size(); i++) {
                MarketdataCandle cur = bars.get(i);
                MarketdataCandle prev = bars.get(i - 1);
                BigDecimal hl = cur.getHighPrice().subtract(cur.getLowPrice()).abs();
                BigDecimal hc = cur.getHighPrice().subtract(prev.getClosePrice()).abs();
                BigDecimal lc = cur.getLowPrice().subtract(prev.getClosePrice()).abs();
                trs.add(hl.max(hc).max(lc));
            }
            BigDecimal atr = Ema.sma(trs.subList(0, period));
            for (int i = period; i < trs.size(); i++) {
                atr = atr.multiply(BigDecimal.valueOf(period - 1)).add(trs.get(i)).divide(BigDecimal.valueOf(period), MC);
            }
            return atr;
        }
    }
}
