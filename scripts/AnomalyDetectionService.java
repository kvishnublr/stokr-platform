package com.stokr.marketdata.tick;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects sudden stock movements at tick level (Option C).
 *
 * Detection types:
 *   VOLUME_SURGE      — tick volume > 3σ above rolling 20-tick volume mean
 *   PRICE_ACCEL       — 2-tick price change / 20-tick avg change > 2.0
 *   VWAP_DEVIATION    — price > VWAP + 1.5σ (or < VWAP - 1.5σ) in <30s
 *   NARROW_BREAKOUT   — 5-min ATR < 0.3% followed by sudden range expansion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final TickAnomalyRepository anomalyRepo;
    private final TickDataRepository tickDataRepo;

    // VWAP and ATR tracking per symbol (updated every minute from tick candles)
    private final Map<String, VwapState> vwapCache = new ConcurrentHashMap<>();
    private final Map<String, double[]> atrCache = new ConcurrentHashMap<>();  // 5-min ATR

    static class VwapState {
        BigDecimal cumulativeTpV = BigDecimal.ZERO;  // sum(typical_price * volume)
        BigDecimal cumulativeV   = BigDecimal.ZERO;  // sum(volume)
        int barCount = 0;

        synchronized void addBar(BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
            BigDecimal tp = high.add(low).add(close).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
            BigDecimal vol = BigDecimal.valueOf(volume);
            cumulativeTpV = cumulativeTpV.add(tp.multiply(vol));
            cumulativeV = cumulativeV.add(vol);
            barCount++;
        }

        synchronized BigDecimal vwap() {
            return cumulativeV.compareTo(ZERO) > 0
                ? cumulativeTpV.divide(cumulativeV, 2, RoundingMode.HALF_UP)
                : ZERO;
        }

        synchronized void reset() {
            cumulativeTpV = ZERO;
            cumulativeV = ZERO;
            barCount = 0;
        }
    }

    @PostConstruct
    public void init() {
        log.info("AnomalyDetectionService started");
    }

    public void evaluate(TickData tick, TickAggregatorService agg) {
        String symbol = tick.getSymbol();
        BigDecimal ltp = tick.getLtp();
        long volDelta = Math.max(0, tick.getVolume() - agg.getCurrentVolume(symbol));

        // ─── VOLUME SURGE ────────────────────────────────────────────────
        TickAggregatorService.RollingWindow volWin = agg.getVolumeWindow(symbol);
        if (volWin != null && volWin.size() >= 5) {
            double mean = volWin.mean();
            double std = volWin.stddev();
            if (std > 0) {
                double zScore = (volDelta - mean) / std;
                if (zScore > 3.0) {
                    recordAnomaly(symbol, "VOLUME_SURGE", ltp, BigDecimal.valueOf(zScore),
                        tick.getVolume(), ZERO, volDelta > 0 ? "UP" : "DOWN");
                }
            }
        }

        // ─── PRICE ACCELERATION ──────────────────────────────────────────
        TickAggregatorService.RollingWindow priceWin = agg.getPriceWindow(symbol);
        if (priceWin != null && priceWin.size() >= 3) {
            double latest2Tick = Math.abs(priceWin.latest());
            double avg20 = Math.abs(priceWin.mean());
            if (avg20 > 0 && latest2Tick / avg20 > 2.5) {
                recordAnomaly(symbol, "PRICE_ACCEL", ltp,
                    BigDecimal.valueOf(latest2Tick / avg20),
                    tick.getVolume(), ZERO, priceWin.latest() > 0 ? "UP" : "DOWN");
            }
        }

        // ─── VWAP DEVIATION ──────────────────────────────────────────────
        VwapState vwapState = vwapCache.get(symbol);
        if (vwapState != null) {
            BigDecimal vwap = vwapState.vwap();
            if (vwap.compareTo(ZERO) > 0) {
                double devPct = ltp.subtract(vwap).divide(vwap, 6, RoundingMode.HALF_UP).doubleValue();
                if (Math.abs(devPct) > 0.003) {
                    recordAnomaly(symbol, "VWAP_DEVIATION", ltp,
                        BigDecimal.valueOf(devPct).multiply(BigDecimal.valueOf(100)),
                        tick.getVolume(), BigDecimal.valueOf(devPct),
                        devPct > 0 ? "UP" : "DOWN");
                }
            }
        }
    }

    public void evaluatePartial(String symbol, TickAggregatorService.MinuteAggState state,
                                 TickAggregatorService agg) {
        // Check for NARROW_BREAKOUT: small 5-min ATR followed by sudden range expansion
        double[] atr5 = atrCache.get(symbol);
        if (atr5 != null) {
            double atrPct = atr5[0]; // 5-min ATR as % of price
            double currRangePct = state.high.subtract(state.low)
                .divide(state.open, 6, RoundingMode.HALF_UP).doubleValue();
            if (atrPct < 0.003 && currRangePct > atrPct * 3) {
                BigDecimal direction = state.close.compareTo(state.open) > 0
                    ? BigDecimal.ONE : BigDecimal.valueOf(-1);
                recordAnomaly(symbol, "NARROW_BREAKOUT", state.close,
                    BigDecimal.valueOf(currRangePct / atrPct),
                    state.volume, ZERO, direction.compareTo(ZERO) > 0 ? "UP" : "DOWN");
            }
        }
    }

    /**
     * Called every minute by the aggregator to update VWAP and ATR from finalized tick candles.
     */
    public void updateMetrics(TickCandleData candle) {
        String symbol = candle.getSymbol();
        VwapState vs = vwapCache.computeIfAbsent(symbol, k -> new VwapState());
        vs.addBar(candle.getHigh(), candle.getLow(), candle.getClose(), candle.getVolume());

        updateAtr(symbol, candle.getHigh(), candle.getLow(), candle.getClose());
    }

    private void updateAtr(String symbol, BigDecimal high, BigDecimal low, BigDecimal close) {
        double[] trs = atrCache.computeIfAbsent(symbol, k -> new double[]{0, 0, 0});
        double prevClose = trs[2] > 0 ? trs[2] : close.doubleValue();
        double tr = Math.max(high.doubleValue() - low.doubleValue(),
            Math.max(Math.abs(high.doubleValue() - prevClose),
                     Math.abs(low.doubleValue() - prevClose)));
        double atr = trs[0] > 0 ? (trs[0] * 4 + tr) / 5 : tr;  // EMA of TR over 5 bars
        double atrPct = atr / close.doubleValue();
        trs[0] = atr;
        trs[1] = atrPct;
        trs[2] = close.doubleValue();
    }

    private static final BigDecimal MAX_MAGNITUDE = new BigDecimal("9999999999.9999");
    private static final BigDecimal MAX_VWAP_DEV = new BigDecimal("9999.9999");

    private void recordAnomaly(String symbol, String type, BigDecimal price, BigDecimal magnitude,
                                long volume, BigDecimal vwapDev, String direction) {
        if (magnitude == null || magnitude.compareTo(MAX_MAGNITUDE) > 0) {
            magnitude = MAX_MAGNITUDE;
        }
        if (vwapDev != null && vwapDev.abs().compareTo(MAX_VWAP_DEV) > 0) {
            vwapDev = vwapDev.signum() >= 0 ? MAX_VWAP_DEV : MAX_VWAP_DEV.negate();
        }
        var anomaly = TickAnomaly.builder()
            .symbol(symbol)
            .anomalyType(type)
            .detectedTs(LocalDateTime.now(IST))
            .priceAtEvent(price)
            .magnitude(magnitude)
            .volumeAtEvent(volume)
            .vwapDeviation(vwapDev)
            .direction(direction)
            .confirmed(false)
            .resolved(false)
            .createdAt(Instant.now())
            .build();
        anomalyRepo.save(anomaly);
        log.info("Anomaly: {} {} @ {} mag={} vol={} dir={}",
            type, symbol, price, String.format("%.2f", magnitude), volume, direction);
    }
}
