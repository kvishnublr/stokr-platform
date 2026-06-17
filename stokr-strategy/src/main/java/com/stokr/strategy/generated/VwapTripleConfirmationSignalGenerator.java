package com.stokr.strategy.generated;

import com.stokr.marketdata.chartink.ChartinkAlertStore;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "VWAP_TRIPLE_CONFIRMATION",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class VwapTripleConfirmationSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 180;
    private static final int MIN_BARS_FOR_VWAP = 30;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final OrderBookPressureTracker pressureTracker;
    private final ChartinkAlertStore chartinkAlertStore;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.vwaptriple.touch-threshold-pct:0.15}")
    private double touchThresholdPct;

    @Value("${stokr.strategy.vwaptriple.min-slope-pct:0.003}")
    private double minSlopePct;

    @Value("${stokr.strategy.vwaptriple.min-volume-multiple:1.3}")
    private double minVolumeMultiple;

    @Value("${stokr.strategy.vwaptriple.bounce-confirm-pct:0.03}")
    private double bounceConfirmPct;

    @Value("${stokr.strategy.vwaptriple.stop-loss-pct:0.40}")
    private double stopLossPct;

    @Value("${stokr.strategy.vwaptriple.target-pct:0.60}")
    private double targetPct;

    @Value("${stokr.strategy.vwaptriple.cooldown-seconds:600}")
    private int cooldownSeconds;

    @Value("${stokr.strategy.vwaptriple.min-risk-reward:1.4}")
    private double minRiskReward;

    @Value("${stokr.strategy.vwaptriple.min-pressure-buy:0.50}")
    private double minPressureBuy;

    @Value("${stokr.strategy.vwaptriple.max-pressure-sell:0.50}")
    private double maxPressureSell;

    @Override
    public String key() { return "VWAP_TRIPLE_CONFIRMATION"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        if (context.asOf() != null) {
            LocalTime lt = context.asOf().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(10, 0)) || lt.isAfter(LocalTime.of(15, 0))) {
                return hold(context);
            }
        }

        // Require a recent Chartink alert for this symbol
        if (!chartinkAlertStore.hasRecentAlert(symbol, Duration.ofMinutes(5))) {
            return hold(context);
        }

        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < MIN_BARS_FOR_VWAP) return hold(context);

        int sessionStart = findSessionStart(bars);
        if (sessionStart < 0 || (bars.size() - sessionStart) < MIN_BARS_FOR_VWAP) return hold(context);

        int n = bars.size();
        double cumPV = 0, cumVol = 0, cumPriceSq = 0;
        double[] vwapArr = new double[n - sessionStart];
        double[] sigmaArr = new double[n - sessionStart];
        double totalVol = 0;

        for (int i = sessionStart; i < n; i++) {
            MarketdataCandle bar = bars.get(i);
            double high = toDouble(bar.getHighPrice());
            double low = toDouble(bar.getLowPrice());
            double close = toDouble(bar.getClosePrice());
            double vol = toDouble(bar.getVolume());
            if (high <= 0 || low <= 0 || close <= 0) continue;

            double tp = (high + low + close) / 3.0;
            cumPV += tp * vol;
            cumVol += vol;
            totalVol += vol;

            int idx = i - sessionStart;
            vwapArr[idx] = cumVol > 0 ? cumPV / cumVol : tp;
            cumPriceSq += (close - vwapArr[idx]) * (close - vwapArr[idx]);
            sigmaArr[idx] = Math.sqrt(cumPriceSq / (idx + 1));
        }

        int lastIdx = vwapArr.length - 1;
        if (lastIdx < 10) return hold(context);

        double currentVwap = vwapArr[lastIdx];
        double currentSigma = sigmaArr[lastIdx];
        double currentPrice = toDouble(bars.get(n - 1).getClosePrice());
        double currentVolume = toDouble(bars.get(n - 1).getVolume());
        double currentHigh = toDouble(bars.get(n - 1).getHighPrice());
        double currentLow = toDouble(bars.get(n - 1).getLowPrice());
        if (currentVwap <= 0 || currentSigma <= 0) return hold(context);

        // CONFIRMATION 1: VWAP TOUCH
        double touchDistPct = Math.abs(currentPrice - currentVwap) / currentVwap * 100;
        boolean priceAtVwap = touchDistPct <= touchThresholdPct;
        boolean priceCrossedVwap = (currentLow <= currentVwap && currentHigh >= currentVwap);

        if (!priceAtVwap && !priceCrossedVwap) {
            return hold(context);
        }

        // CONFIRMATION 2: VWAP SLOPE — must be clearly trending
        int slopeWindow = Math.min(15, lastIdx);
        double prevVwap = vwapArr[lastIdx - slopeWindow];
        double slopePct = (currentVwap - prevVwap) / prevVwap * 100;
        if (Math.abs(slopePct) < minSlopePct * slopeWindow) {
            return hold(context);
        }

        boolean isUptrend = slopePct > 0;

        // CONFIRMATION 3: BOUNCE CANDLE — price must be on correct side of VWAP
        if (n < 3) return hold(context);
        MarketdataCandle curBar = bars.get(n - 1);
        MarketdataCandle prevBar = bars.get(n - 2);
        double curClose = toDouble(curBar.getClosePrice());
        double curOpen = toDouble(curBar.getOpenPrice());

        boolean bounceConfirmed;
        if (isUptrend) {
            bounceConfirmed = curClose > curOpen
                    && curClose > currentVwap
                    && (curClose - currentVwap) / currentVwap * 100 >= bounceConfirmPct;
        } else {
            bounceConfirmed = curClose < curOpen
                    && curClose < currentVwap
                    && (currentVwap - curClose) / currentVwap * 100 >= bounceConfirmPct;
        }
        if (!bounceConfirmed) {
            return hold(context);
        }

        // CONFIRMATION 4: VOLUME SURGE
        double avgVol = totalVol / (lastIdx + 1);
        if (avgVol > 0 && currentVolume / avgVol < minVolumeMultiple) {
            return hold(context);
        }

        // CONFIRMATION 5: ORDER BOOK PRESSURE
        var snapshot = pressureTracker.getSnapshot(symbol);
        double imb = resolvePressure(snapshot != null ? snapshot.imbalanceRatio() : null, bars, 5);
        if (isUptrend && imb < minPressureBuy) {
            return hold(context);
        }
        if (!isUptrend && imb > maxPressureSell) {
            return hold(context);
        }

        // COOLDOWN
        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null) {
            long sinceLast = Duration.between(lastEmit, now).getSeconds();
            if (sinceLast >= 0 && sinceLast < cooldownSeconds) {
                return hold(context);
            }
        }

        // SIGNAL with percentage-based SL/Target
        SignalType signalType;
        double entryPrice = currentPrice;
        double stopLoss, target;

        if (isUptrend) {
            signalType = SignalType.BUY;
            stopLoss = entryPrice * (1 - stopLossPct / 100);
            target = entryPrice * (1 + targetPct / 100);
        } else {
            signalType = SignalType.SELL;
            stopLoss = entryPrice * (1 + stopLossPct / 100);
            target = entryPrice * (1 - targetPct / 100);
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double rr = risk > 0 ? reward / risk : 0;
        if (rr < minRiskReward) return hold(context);
        if (risk / entryPrice > 0.03) return hold(context);
        if (risk / entryPrice < 0.0005) return hold(context);

        lastEmitBySymbol.put(symbol, now);

        String reason = String.format(
            "VWAP_TRIPLE %s: vwap=%.2f sigma=%.2f touch=%.3f%% slope=%.3f%% " +
            "vol=%.1fx imb=%.0f%% entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            signalType, currentVwap, currentSigma, touchDistPct, slopePct,
            avgVol > 0 ? currentVolume / avgVol : 0, imb * 100,
            entryPrice, stopLoss, target, rr
        );

        log.info("vwaptriple.signal symbol={} {}", symbol, reason);
        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(stopLoss), BigDecimal.valueOf(target));
    }

    private int findSessionStart(List<MarketdataCandle> bars) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (bars.get(i).getOpenTime() == null) continue;
            LocalTime lt = bars.get(i).getOpenTime().atZone(zone).toLocalTime();
            if (lt.isBefore(LocalTime.of(9, 16)) && lt.isAfter(LocalTime.of(9, 14))) return i;
        }
        return -1;
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
