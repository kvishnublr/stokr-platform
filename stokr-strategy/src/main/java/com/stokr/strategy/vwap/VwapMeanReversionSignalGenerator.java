package com.stokr.strategy.vwap;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VwapMeanReversionSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    // Minimum deviation from VWAP to trigger — 1.5% filters out noise
    @Value("${stokr.vwap.deviation-pct:0.015}")
    private BigDecimal deviationPct;

    // RSI must be oversold for BUY (<40), overbought for SELL (>60)
    @Value("${stokr.vwap.rsi-oversold:40}")
    private double rsiOversold;

    @Value("${stokr.vwap.rsi-overbought:60}")
    private double rsiOverbought;

    // Minimum price to avoid penny stocks
    @Value("${stokr.vwap.min-price:20}")
    private BigDecimal minPrice;

    // ATR multiplier for SL and target sizing
    @Value("${stokr.vwap.sl-atr-mult:1.5}")
    private double slAtrMult;

    @Value("${stokr.vwap.target-atr-mult:2.0}")
    private double targetAtrMult;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 240, barOpenTime);
        if (bars.size() < 40) {
            return null;
        }

        MarketdataCandle last = bars.getLast();
        if (last.getClosePrice() == null || last.getClosePrice().compareTo(minPrice) < 0) {
            return null; // skip penny stocks / no-data
        }

        // Session check
        ZonedDateTime eval = barOpenTime.atZone(zone);
        LocalTime lt = eval.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
            return null;
        }

        // Calculate VWAP from session open
        LocalDate day = eval.toLocalDate();
        Instant open = day.atTime(sessionStart).atZone(zone).toInstant();
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal vol = BigDecimal.ZERO;
        for (MarketdataCandle c : bars) {
            if (c.getOpenTime().isBefore(open) || c.getOpenTime().isAfter(barOpenTime)) continue;
            if (c.getHighPrice() == null || c.getLowPrice() == null || c.getClosePrice() == null) continue;
            BigDecimal tp = c.getHighPrice().add(c.getLowPrice()).add(c.getClosePrice())
                    .divide(BigDecimal.valueOf(3), MC);
            BigDecimal v = c.getVolume() != null ? c.getVolume() : BigDecimal.ONE;
            pv = pv.add(tp.multiply(v));
            vol = vol.add(v);
        }
        if (vol.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal vwap = pv.divide(vol, MC);

        // Deviation from VWAP
        BigDecimal dist = last.getClosePrice().subtract(vwap).divide(last.getClosePrice(), MC).abs();
        if (dist.compareTo(deviationPct) < 0) {
            return null; // not far enough from VWAP
        }

        boolean below = last.getClosePrice().compareTo(vwap) < 0;

        // RSI confirmation — BUY only when oversold, SELL only when overbought
        double rsi = calcRsi(bars);
        if (below && rsi > rsiOversold) {
            return null; // price below VWAP but RSI not oversold — could keep falling
        }
        if (!below && rsi < rsiOverbought) {
            return null; // price above VWAP but RSI not overbought — could keep rising
        }

        // ATR for SL/Target sizing
        double atr = calcAtr(bars, ATR_PERIOD);
        if (atr <= 0) {
            return null;
        }
        BigDecimal atrBd = BigDecimal.valueOf(atr).setScale(4, RoundingMode.HALF_UP);
        BigDecimal slDist     = atrBd.multiply(BigDecimal.valueOf(slAtrMult));
        BigDecimal targetDist = atrBd.multiply(BigDecimal.valueOf(targetAtrMult));

        BigDecimal entry = last.getClosePrice();
        BigDecimal sl     = below ? entry.subtract(slDist)     : entry.add(slDist);
        BigDecimal target = below ? entry.add(targetDist)      : entry.subtract(targetDist);

        // Confidence: scale by how far deviation is vs threshold (more stretched = higher confidence)
        double devRatio = dist.doubleValue() / deviationPct.doubleValue();
        double confidence = Math.min(0.85, 0.55 + (devRatio - 1.0) * 0.10);

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.VWAP_MEAN_REVERSION);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSignalType(below ? SignalType.BUY : SignalType.SELL);
        sig.setConfidenceScore(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP));
        sig.setReasonText(String.format("%s VWAP dev=%.2f%% RSI=%.1f",
                below ? "BUY: oversold below" : "SELL: overbought above",
                dist.doubleValue() * 100, rsi));
        sig.setEntryReferencePrice(entry.setScale(2, RoundingMode.HALF_UP));
        sig.setSuggestedQty(BigDecimal.ONE);
        sig.setStopPrice(sl.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        sig.setTargetPrice(target.setScale(2, RoundingMode.HALF_UP));
        // Store indicators
        sig.setRsiValue(BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP));
        sig.setAtrValue(atrBd);
        sig.setVwapDistance(dist.setScale(6, RoundingMode.HALF_UP));
        return sig;
    }

    private double calcRsi(List<MarketdataCandle> bars) {
        int n = bars.size();
        if (n < RSI_PERIOD + 1) return 50.0;
        double gain = 0, loss = 0;
        int start = n - RSI_PERIOD - 1;
        for (int i = start; i < n - 1; i++) {
            if (bars.get(i).getClosePrice() == null || bars.get(i + 1).getClosePrice() == null) continue;
            double delta = bars.get(i + 1).getClosePrice().subtract(bars.get(i).getClosePrice()).doubleValue();
            if (delta > 0) gain += delta; else loss -= delta;
        }
        gain /= RSI_PERIOD;
        loss /= RSI_PERIOD;
        if (loss == 0) return 100.0;
        double rs = gain / loss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calcAtr(List<MarketdataCandle> bars, int period) {
        int n = bars.size();
        if (n < period + 1) return 0;
        double sum = 0;
        int start = n - period - 1;
        for (int i = start; i < n - 1; i++) {
            MarketdataCandle c = bars.get(i + 1);
            MarketdataCandle prev = bars.get(i);
            if (c.getHighPrice() == null || c.getLowPrice() == null || prev.getClosePrice() == null) continue;
            double hl  = c.getHighPrice().subtract(c.getLowPrice()).doubleValue();
            double hpc = Math.abs(c.getHighPrice().subtract(prev.getClosePrice()).doubleValue());
            double lpc = Math.abs(c.getLowPrice().subtract(prev.getClosePrice()).doubleValue());
            sum += Math.max(hl, Math.max(hpc, lpc));
        }
        return sum / period;
    }
}
