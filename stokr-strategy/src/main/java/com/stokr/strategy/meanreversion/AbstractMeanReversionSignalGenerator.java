package com.stokr.strategy.meanreversion;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
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
        return evaluateFromBars(symbol, bars1m, bars5m, userId, backtestRunId, pipeline, evaluationZ, vwapDay);
    }

    /**
     * Deterministic evaluation at a historical 1m candle open time (replay/backtests).
     */
    public StrategySignalEntity evaluatePersistableAtOpen(String symbol, java.util.UUID userId, java.util.UUID backtestRunId, String pipeline, Instant barOpenTime) {
        List<MarketdataCandle> bars1m = marketDataQueryService.lastBarsAscEndingAt(symbol, "1m", 240, barOpenTime);
        List<MarketdataCandle> bars5m = marketDataQueryService.lastBarsAscEndingAt(symbol, "5m", 120, barOpenTime);
        ZonedDateTime evaluationZ = barOpenTime.atZone(zone);
        LocalDate vwapDay = evaluationZ.toLocalDate();
        return evaluateFromBars(symbol, bars1m, bars5m, userId, backtestRunId, pipeline, evaluationZ, vwapDay);
    }

    private StrategySignalEntity evaluateFromBars(
            String symbol,
            List<MarketdataCandle> bars1m,
            List<MarketdataCandle> bars5m,
            java.util.UUID userId,
            java.util.UUID backtestRunId,
            String pipeline,
            ZonedDateTime evaluationZ,
            LocalDate vwapDay
    ) {
        MeanReversionParams p = variant();
        if (bars1m.size() < 60 || bars5m.size() < 10) {
            return null;
        }

        LocalTime lt = evaluationZ.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
            return null;
        }

        MarketdataCandle last = bars1m.getLast();
        List<BigDecimal> closes = closes(bars1m);

        BigDecimal rsi = WilderRsi.last(closes, 14);
        BigDecimal atr = WilderAtr.last(bars1m, 14);
        BigDecimal ema20 = Ema.last(closes, 20);

        SessionVwap vwap = SessionVwap.compute(bars1m, zone, vwapDay);

        RangeBounds range = RangeBounds.lastN(bars1m, 20);

        MarketRegime regime = MarketRegimeDetector.detect(bars1m, bars5m, atr, ema20, vwap.value(), range);

        BigDecimal widthPct = range.width().divide(last.getClosePrice(), MC).multiply(BigDecimal.valueOf(100), MC);

        boolean atrCompressed = atr.compareTo(BigDecimal.ZERO) > 0
                && atr.divide(last.getClosePrice(), MC).compareTo(new BigDecimal("0.004")) < 0;

        boolean nearVwap = vwap.value().compareTo(BigDecimal.ZERO) > 0
                && last.getClosePrice().subtract(vwap.value()).abs()
                .divide(last.getClosePrice(), MC)
                .compareTo(new BigDecimal("0.003")) < 0;

        boolean emaFlat = ema20 != null && last.getClosePrice().subtract(ema20).abs()
                .divide(last.getClosePrice(), MC)
                .compareTo(new BigDecimal("0.0025")) < 0;

        boolean sideways =
                regime == MarketRegime.SIDEWAYS
                        && atrCompressed
                        && emaFlat
                        && nearVwap
                        && widthPct.compareTo(p.maxRangeWidthPct()) < 0;

        if (!sideways) {
            return null;
        }

        BigDecimal eps = last.getClosePrice().multiply(new BigDecimal("0.0005"), MC).max(new BigDecimal("0.5"));

        boolean touchLow = last.getLowPrice().subtract(range.low()).abs().compareTo(eps) <= 0;
        boolean touchHigh = last.getHighPrice().subtract(range.high()).abs().compareTo(eps) <= 0;

        boolean bullishRejection = last.getClosePrice().compareTo(last.getOpenPrice()) > 0
                && last.getOpenPrice().subtract(last.getLowPrice()).compareTo(last.getClosePrice().subtract(last.getOpenPrice()).multiply(BigDecimal.valueOf(2))) > 0;

        boolean bearishRejection = last.getClosePrice().compareTo(last.getOpenPrice()) < 0
                && last.getHighPrice().subtract(last.getOpenPrice()).compareTo(last.getOpenPrice().subtract(last.getLowPrice()).multiply(BigDecimal.valueOf(2))) > 0;

        boolean belowVwap = last.getClosePrice().compareTo(vwap.value()) < 0;
        boolean aboveVwap = last.getClosePrice().compareTo(vwap.value()) > 0;

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(p.catalogStrategyKey());
        sig.setStrategyVersion(p.strategyVersion());
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
                + "\",\"rangeHigh\":\"" + range.high().toPlainString() + "\",\"rangeLow\":\"" + range.low().toPlainString() + "\"}";
        sig.setParameterSnapshotJson(p.toSnapshotJson());
        sig.setIndicatorSnapshotJson(indicatorSnap);

        if (touchLow && rsi.compareTo(p.rsiBuyMax()) < 0 && bullishRejection && belowVwap) {
            sig.setSignalType(SignalType.BUY);
            sig.setConfidenceScore(p.confidenceScore());
            sig.setRejectionPattern("BULL_REJECTION");
            sig.setReasonText("Range-low fade long (" + p.catalogStrategyKey() + ")");
            BigDecimal stop = last.getLowPrice().min(last.getOpenPrice());
            sig.setStopPrice(stop);
            BigDecimal mid = range.mid();
            BigDecimal rrTarget = computeMinRrTarget(last.getClosePrice(), stop, mid, true);
            sig.setTargetPrice(rrTarget);
            sig.setEntryReferencePrice(last.getClosePrice());
            sig.setSuggestedQty(BigDecimal.ONE);
            return sig;
        }

        if (touchHigh && rsi.compareTo(p.rsiSellMin()) > 0 && bearishRejection && aboveVwap) {
            sig.setSignalType(SignalType.SELL);
            sig.setConfidenceScore(p.confidenceScore());
            sig.setRejectionPattern("BEAR_REJECTION");
            sig.setReasonText("Range-high fade short (" + p.catalogStrategyKey() + ")");
            BigDecimal stop = last.getHighPrice().max(last.getOpenPrice());
            sig.setStopPrice(stop);
            BigDecimal mid = range.mid();
            BigDecimal rrTarget = computeMinRrTarget(last.getClosePrice(), stop, mid, false);
            sig.setTargetPrice(rrTarget);
            sig.setEntryReferencePrice(last.getClosePrice());
            sig.setSuggestedQty(BigDecimal.ONE);
            return sig;
        }

        return null;
    }

    private static BigDecimal computeMinRrTarget(BigDecimal entry, BigDecimal stop, BigDecimal midpoint, boolean isLong) {
        BigDecimal risk = entry.subtract(stop).abs();
        BigDecimal rrMinTarget = isLong ? entry.add(risk.multiply(new BigDecimal("1.5"))) : entry.subtract(risk.multiply(new BigDecimal("1.5")));
        if (isLong) {
            BigDecimal best = midpoint.max(rrMinTarget);
            return best.min(entry.add(entry.multiply(new BigDecimal("0.02")))); // sanity cap
        }
        BigDecimal best = midpoint.min(rrMinTarget);
        return best.max(entry.subtract(entry.multiply(new BigDecimal("0.02"))));
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
                BigDecimal slope = ema20.subtract(Ema.at(m1.size() - 6 >= 0 ? closes(m1.subList(0, m1.size() - 5)) : closes(m1), 20, Math.min(5, m1.size() - 1)));
                if (slope.abs().divide(last1.getClosePrice(), MC).compareTo(new BigDecimal("0.004")) > 0) {
                    return MarketRegime.TRENDING;
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
