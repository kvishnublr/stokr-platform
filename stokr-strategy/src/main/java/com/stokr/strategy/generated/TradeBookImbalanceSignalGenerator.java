package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
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
    strategyKey  = "TRADE_BOOK_IMBALANCE",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class TradeBookImbalanceSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 60;
    private static final int MIN_BARS = 10;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final OrderBookPressureTracker pressureTracker;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.tradebookimb.min-session-hour:10}")
    private int minSessionHour;

    @Value("${stokr.strategy.tradebookimb.max-session-hour:14}")
    private int maxSessionHour;

    @Value("${stokr.strategy.tradebookimb.max-session-minute:30}")
    private int maxSessionMinute;

    @Value("${stokr.strategy.tradebookimb.buy-threshold:0.55}")
    private double buyThreshold;

    @Value("${stokr.strategy.tradebookimb.sell-threshold:0.45}")
    private double sellThreshold;

    @Value("${stokr.strategy.tradebookimb.min-volume-multiple:1.3}")
    private double minVolumeMultiple;

    @Value("${stokr.strategy.tradebookimb.stop-loss-pct:0.35}")
    private double stopLossPct;

    @Value("${stokr.strategy.tradebookimb.target-rr:1.5}")
    private double targetRr;

    @Value("${stokr.strategy.tradebookimb.cooldown-seconds:300}")
    private int cooldownSeconds;

    @Override
    public String key() { return "TRADE_BOOK_IMBALANCE"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(LocalTime.of(minSessionHour, 0)) ||
            lt.isAfter(LocalTime.of(maxSessionHour, maxSessionMinute))) {
            return hold(context);
        }

        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < MIN_BARS) return hold(context);

        int n = bars.size();
        MarketdataCandle curBar = bars.get(n - 1);
        double currentPrice = toDouble(curBar.getClosePrice());
        double currentVolume = toDouble(curBar.getVolume());
        if (currentPrice <= 0) return hold(context);

        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        double imb = resolvePressure(snapshot != null ? snapshot.imbalanceRatio() : null, bars, 5);

        boolean isBuyPressure = imb >= buyThreshold;
        boolean isSellPressure = imb <= sellThreshold;
        if (!isBuyPressure && !isSellPressure) {
            return hold(context);
        }

        boolean isLong = isBuyPressure;

        double curClose = toDouble(curBar.getClosePrice());
        double curOpen = toDouble(curBar.getOpenPrice());
        boolean candleConfirms = isLong ? curClose > curOpen : curClose < curOpen;
        if (!candleConfirms) {
            return hold(context);
        }

        double totalVol = 0;
        for (int i = 0; i < n; i++) totalVol += toDouble(bars.get(i).getVolume());
        double avgVol = totalVol / n;
        if (avgVol > 0 && currentVolume / avgVol < minVolumeMultiple) {
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

        double entryPrice = currentPrice;
        double stopLoss, target;
        if (isLong) {
            stopLoss = entryPrice * (1 - stopLossPct / 100);
            target = entryPrice * (1 + stopLossPct / 100 * targetRr);
        } else {
            stopLoss = entryPrice * (1 + stopLossPct / 100);
            target = entryPrice * (1 - stopLossPct / 100 * targetRr);
        }

        double risk = Math.abs(entryPrice - stopLoss);
        double reward = Math.abs(target - entryPrice);
        double rr = risk > 0 ? reward / risk : 0;
        if (rr < 1.3) return hold(context);
        if (risk / entryPrice > 0.025) return hold(context);
        if (risk / entryPrice < 0.0005) return hold(context);

        lastEmitBySymbol.put(symbol, now);

        SignalType signalType = isLong ? SignalType.BUY : SignalType.SELL;
        String reason = String.format(
            "TRADE_BOOK_IMB %s: imb=%.0f%% vol=%.1fx entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            signalType, imb * 100, avgVol > 0 ? currentVolume / avgVol : 0,
            entryPrice, stopLoss, target, rr
        );

        log.info("tradebookimb.signal symbol={} {}", symbol, reason);
        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(stopLoss), BigDecimal.valueOf(target));
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
