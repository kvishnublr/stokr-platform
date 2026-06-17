package com.stokr.strategy.generated;

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
    strategyKey  = "MORNING_SURGE",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class MorningSurgeSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 120;
    private static final int MIN_BARS = 20;
    private static final int SURGE_LOOKBACK = 10;
    private static final int SURGE_WINDOW = 5;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.morningsurge.min-surge-pct:0.50}")
    private double minSurgePct;

    @Value("${stokr.strategy.morningsurge.min-volume-multiple:2.0}")
    private double minVolumeMultiple;

    @Value("${stokr.strategy.morningsurge.stop-loss-pct:0.40}")
    private double stopLossPct;

    @Value("${stokr.strategy.morningsurge.target-rr:1.5}")
    private double targetRr;

    @Value("${stokr.strategy.morningsurge.cooldown-seconds:600}")
    private int cooldownSeconds;

    @Value("${stokr.strategy.morningsurge.min-pressure-buy:0.52}")
    private double minPressureBuy;

    @Value("${stokr.strategy.morningsurge.max-pressure-sell:0.48}")
    private double maxPressureSell;

    @Override
    public String key() { return "MORNING_SURGE"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(LocalTime.of(9, 30)) || lt.isAfter(LocalTime.of(11, 0))) {
            return hold(context);
        }

        Instant now = context.asOf() != null ? context.asOf() : Instant.now();
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null) {
            long sinceLast = Duration.between(lastEmit, now).getSeconds();
            if (sinceLast >= 0 && sinceLast < cooldownSeconds) {
                return hold(context);
            }
        }

        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < MIN_BARS) return hold(context);

        int n = bars.size();
        if (n < SURGE_LOOKBACK + 1) return hold(context);

        MarketdataCandle curBar = bars.get(n - 1);
        double currentPrice = toDouble(curBar.getClosePrice());
        double currentVolume = toDouble(curBar.getVolume());
        if (currentPrice <= 0) return hold(context);

        double surgeStart = toDouble(bars.get(n - SURGE_LOOKBACK).getClosePrice());
        double surgePct = (currentPrice - surgeStart) / surgeStart * 100;

        boolean isSurgeUp = surgePct > 0;
        double absSurgePct = Math.abs(surgePct);
        if (absSurgePct < minSurgePct) {
            return hold(context);
        }

        double totalVol = 0;
        for (int i = 0; i < n; i++) totalVol += toDouble(bars.get(i).getVolume());
        double avgVol = totalVol / n;
        if (avgVol > 0 && currentVolume / avgVol < minVolumeMultiple) {
            return hold(context);
        }

        double surgeVol = 0;
        for (int i = n - SURGE_WINDOW; i < n; i++) {
            surgeVol += toDouble(bars.get(i).getVolume());
        }
        double surgeAvgVol = surgeVol / SURGE_WINDOW;
        if (avgVol > 0 && surgeAvgVol / avgVol < minVolumeMultiple) {
            return hold(context);
        }

        var snapshot = pressureTracker.getSnapshot(symbol);
        double imb = resolvePressure(snapshot != null ? snapshot.imbalanceRatio() : null, bars, 5);
        if (isSurgeUp && imb < minPressureBuy) {
            return hold(context);
        }
        if (!isSurgeUp && imb > maxPressureSell) {
            return hold(context);
        }

        MarketdataCandle prevBar = bars.get(n - 2);
        double curClose = toDouble(curBar.getClosePrice());
        double curOpen = toDouble(curBar.getOpenPrice());
        double prevClose = toDouble(prevBar.getClosePrice());

        boolean continuation = isSurgeUp
                ? curClose > curOpen && curClose > prevClose
                : curClose < curOpen && curClose < prevClose;
        if (!continuation) {
            return hold(context);
        }

        double entryPrice = currentPrice;
        double stopLoss, target;
        SignalType signalType;

        if (isSurgeUp) {
            signalType = SignalType.BUY;
            stopLoss = entryPrice * (1 - stopLossPct / 100);
            target = entryPrice * (1 + stopLossPct / 100 * targetRr);
        } else {
            signalType = SignalType.SELL;
            stopLoss = entryPrice * (1 + stopLossPct / 100);
            target = entryPrice * (1 - stopLossPct / 100 * targetRr);
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double rr = risk > 0 ? reward / risk : 0;
        if (rr < 1.3) return hold(context);
        if (risk / entryPrice > 0.03) return hold(context);
        if (risk / entryPrice < 0.0005) return hold(context);

        lastEmitBySymbol.put(symbol, now);

        String reason = String.format(
            "MORNING_SURGE %s: surge=%+.2f%% vol=%.1fx imb=%.0f%% entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            signalType, surgePct, avgVol > 0 ? currentVolume / avgVol : 0, imb * 100,
            entryPrice, stopLoss, target, rr
        );

        log.info("morningsurge.signal symbol={} {}", symbol, reason);
        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(stopLoss), BigDecimal.valueOf(target));
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
