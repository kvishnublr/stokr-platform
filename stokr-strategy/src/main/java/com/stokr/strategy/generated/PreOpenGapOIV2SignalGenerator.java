package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.LookbackWindow;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.lifecycle.StrategySessionEntryGuardService;
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

@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "PRE_OPEN_GAP_OI",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class PreOpenGapOIV2SignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final String NIFTY_SYMBOL = "NIFTY 50";
    private static final int SESSION_BARS = 120;
    private static final int PREV_SESSION_BARS = 75;
    private static final LocalTime ENTRY_START = LocalTime.of(9, 16);
    private static final LocalTime ENTRY_END = LocalTime.of(9, 20);

    private final MarketDataQueryService marketDataQueryService;
    private final OrderBookPressureTracker pressureTracker;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final StrategySessionEntryGuardService sessionEntryGuard;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.preopengap.min-gap-pct:0.30}")
    private double minGapPct;

    @Value("${stokr.strategy.preopengap.max-gap-pct:3.0}")
    private double maxGapPct;

    @Value("${stokr.strategy.preopengap.sl-buffer-pct:0.20}")
    private double slBufferPct;

    @Value("${stokr.strategy.preopengap.target-rr:1.8}")
    private double targetRr;

    @Value("${stokr.strategy.preopengap.min-volume-multiple:1.5}")
    private double minVolumeMultiple;

    @Value("${stokr.strategy.preopengap.nifty-trend-threshold:0.05}")
    private double niftyTrendThreshold;

    @Override
    public String key() { return "PRE_OPEN_GAP_OI"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }
        if (!sessionEntryGuard.isSessionEntryAllowed(key(), symbol, asOf)) {
            return hold(context);
        }

        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(ENTRY_START) || lt.isAfter(ENTRY_END)) {
            return hold(context);
        }

        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(
                symbol, TIMEFRAME, SESSION_BARS + PREV_SESSION_BARS);
        if (bars.size() < PREV_SESSION_BARS + 3) return hold(context);

        double prevClose = 0, todayOpen = 0, todayHigh = 0, todayLow = Double.MAX_VALUE;
        int todayStart = -1;

        for (int i = 1; i < bars.size(); i++) {
            if (bars.get(i).getOpenTime() == null || bars.get(i - 1).getOpenTime() == null) continue;
            LocalTime curTime = bars.get(i).getOpenTime().atZone(zone).toLocalTime();
            LocalTime prevTime = bars.get(i - 1).getOpenTime().atZone(zone).toLocalTime();
            boolean isBoundary = (prevTime.isAfter(LocalTime.of(15, 0)) && curTime.isBefore(LocalTime.of(10, 0)))
                || Duration.between(bars.get(i - 1).getOpenTime(), bars.get(i).getOpenTime()).toHours() > 12;
            if (isBoundary) {
                prevClose = toDouble(bars.get(i - 1).getClosePrice());
                todayOpen = toDouble(bars.get(i).getOpenPrice());
                todayStart = i;
                break;
            }
        }

        if (todayStart < 0 || prevClose <= 0 || todayOpen <= 0) return hold(context);

        for (int i = todayStart; i < bars.size(); i++) {
            double h = toDouble(bars.get(i).getHighPrice());
            double l = toDouble(bars.get(i).getLowPrice());
            if (h > todayHigh) todayHigh = h;
            if (l > 0 && l < todayLow) todayLow = l;
        }

        double gapPct = (todayOpen - prevClose) / prevClose * 100.0;
        double absGapPct = Math.abs(gapPct);
        if (absGapPct < minGapPct || absGapPct > maxGapPct) {
            return hold(context);
        }
        boolean isGapUp = gapPct > 0;

        PressureSnapshot snap = pressureTracker.getSnapshot(symbol);
        double pressureRatio = 0.5;
        if (snap != null) {
            pressureRatio = snap.imbalanceRatio();
            boolean aligns = isGapUp ? pressureRatio > 0.52 : pressureRatio < 0.48;
            if (!aligns) return hold(context);
        }

        double niftyTrend = niftyTrend5Min();
        boolean niftyAligns = isGapUp
                ? niftyTrend > niftyTrendThreshold
                : niftyTrend < -niftyTrendThreshold;

        if (bars.size() < todayStart + 2) return hold(context);
        MarketdataCandle firstCandle = bars.get(todayStart);
        double firstClose = toDouble(firstCandle.getClosePrice());
        double firstOpen = toDouble(firstCandle.getOpenPrice());
        boolean candleConfirms = isGapUp ? firstClose > firstOpen : firstClose < firstOpen;
        if (!candleConfirms) return hold(context);

        double avgVol = 0;
        int volSamples = 0;
        for (int i = Math.max(0, todayStart - 20); i < todayStart; i++) {
            double v = toDouble(bars.get(i).getVolume());
            if (v > 0) { avgVol += v; volSamples++; }
        }
        avgVol = volSamples > 0 ? avgVol / volSamples : 0;
        double openVol = toDouble(firstCandle.getVolume());
        if (avgVol > 0 && openVol / avgVol < minVolumeMultiple) return hold(context);

        double currentPrice = toDouble(bars.get(bars.size() - 1).getClosePrice());
        if (currentPrice <= 0) return hold(context);

        double stopLoss, target;
        SignalType signalType;

        if (isGapUp) {
            signalType = SignalType.BUY;
            stopLoss = todayLow > 0 && todayLow < Double.MAX_VALUE
                    ? todayLow * (1 - slBufferPct / 100)
                    : prevClose * (1 - slBufferPct / 100);
            double risk = currentPrice - stopLoss;
            target = currentPrice + risk * targetRr;
        } else {
            signalType = SignalType.SELL;
            stopLoss = todayHigh > 0
                    ? todayHigh * (1 + slBufferPct / 100)
                    : prevClose * (1 + slBufferPct / 100);
            double risk = stopLoss - currentPrice;
            target = currentPrice - risk * targetRr;
        }

        double risk = Math.abs(currentPrice - stopLoss);
        double reward = Math.abs(target - currentPrice);
        double rr = risk > 0 ? reward / risk : 0;
        if (rr < 1.3) return hold(context);
        if (risk / currentPrice > 0.025) return hold(context);
        if (risk / currentPrice < 0.0005) return hold(context);

        String pressureStr = snap != null ? String.format("%.0f%%", pressureRatio * 100) : "N/A";
        String volStr = avgVol > 0 ? String.format("%.1fx", openVol / avgVol) : "N/A";
        String reason = String.format(
            "PRE_OPEN_GAP_OI %s gap=%+.2f%% pressure=%s nifty=%+.2f%% vol=%s entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            signalType, gapPct, pressureStr, niftyTrend, volStr,
            currentPrice, stopLoss, target, rr
        );

        log.info("preopengapoi_v2.signal symbol={} {}", symbol, reason);
        return new StrategySignal(signalType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(currentPrice), BigDecimal.valueOf(stopLoss), BigDecimal.valueOf(target));
    }

    private double niftyTrend5Min() {
        try {
            var result = integrityGate.sessionBars(
                    key(), NIFTY_SYMBOL, TIMEFRAME, 15, 4, LookbackWindow.FIVE_MINUTE, null);
            if (result.isEmpty() || result.get().size() < 5) return 0;
            List<MarketdataCandle> nb = result.get();
            double first = toDouble(nb.get(nb.size() - 5).getClosePrice());
            double last = toDouble(nb.get(nb.size() - 1).getClosePrice());
            return first > 0 ? (last - first) / first * 100 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
