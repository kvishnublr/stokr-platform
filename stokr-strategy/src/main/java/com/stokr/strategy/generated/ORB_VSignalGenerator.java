package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
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
import java.math.RoundingMode;
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
    strategyKey  = "ORB_V",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class ORB_VSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";
    private static final int BARS_FETCH = 360;
    private static final int MIN_BARS = 20;
    private static final int OR_MINUTES = 15;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.orb-v.volume-multiple:1.5}")
    private double volumeMultiple;

    @Value("${stokr.strategy.orb-v.max-stop-pct:0.5}")
    private double maxStopPct;

    @Value("${stokr.strategy.orb-v.target-rr:2.0}")
    private double targetRr;

    @Value("${stokr.strategy.orb-v.cooldown-seconds:21600}")
    private int cooldownSeconds;

    @Override
    public String key() { return "ORB_V"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) return hold(context);

        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(LocalTime.of(9, 30)) || lt.isAfter(LocalTime.of(12, 30))) {
            return hold(context);
        }

        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null) {
            long sinceLast = Duration.between(lastEmit, asOf).getSeconds();
            if (sinceLast >= 0 && sinceLast < cooldownSeconds) {
                return hold(context);
            }
        }

        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < MIN_BARS) return hold(context);

        int sessionStart = findSessionStart(bars);
        if (sessionStart < 0) sessionStart = 0;
        int n = bars.size();
        int sessionLen = n - sessionStart;
        if (sessionLen < OR_MINUTES + 2) return hold(context);

        double orh = Double.NEGATIVE_INFINITY, orl = Double.POSITIVE_INFINITY, orVolSum = 0;
        for (int i = 0; i < OR_MINUTES; i++) {
            MarketdataCandle b = bars.get(sessionStart + i);
            double h = toDouble(b.getHighPrice());
            double l = toDouble(b.getLowPrice());
            if (h <= 0 || l <= 0) return hold(context);
            orh = Math.max(orh, h);
            orl = Math.min(orl, l);
            orVolSum += toDouble(b.getVolume());
        }
        double orAvgVol = orVolSum / OR_MINUTES;
        if (orh <= 0 || orl <= 0 || orAvgVol <= 0) return hold(context);

        MarketdataCandle cur = bars.get(n - 1);
        double curClose = toDouble(cur.getClosePrice());
        double curVol = toDouble(cur.getVolume());
        if (curClose <= 0) return hold(context);

        boolean longBreak = curClose > orh && curVol >= volumeMultiple * orAvgVol;
        boolean shortBreak = curClose < orl && curVol >= volumeMultiple * orAvgVol;
        if (!longBreak && !shortBreak) return hold(context);

        boolean isLong = longBreak;
        double entry = curClose;
        double stop = isLong
                ? Math.max(orl, entry * (1 - maxStopPct / 100))
                : Math.min(orh, entry * (1 + maxStopPct / 100));

        double risk = Math.abs(entry - stop);
        if (risk <= 0 || risk / entry < 0.0008) return hold(context);

        double target = isLong ? entry + targetRr * risk : entry - targetRr * risk;
        double rr = risk > 0 ? Math.abs(target - entry) / risk : 0;
        if (rr < 1.5) return hold(context);

        lastEmitBySymbol.put(symbol, asOf);

        SignalType sigType = isLong ? SignalType.BUY : SignalType.SELL;
        String reason = String.format(
            "ORB_V %s: orh=%.2f orl=%.2f volX=%.1f entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            sigType, orh, orl, curVol / orAvgVol, entry, stop, target, rr);
        log.info("orbv.signal symbol={} {}", symbol, reason);

        return new StrategySignal(sigType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(stop).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(target).setScale(2, RoundingMode.HALF_UP));
    }

    private int findSessionStart(List<MarketdataCandle> bars) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (bars.get(i).getOpenTime() == null) continue;
            LocalTime t = bars.get(i).getOpenTime().atZone(zone).toLocalTime();
            if (t.isAfter(LocalTime.of(9, 14)) && t.isBefore(LocalTime.of(9, 17))) return i;
        }
        return -1;
    }

    private static double toDouble(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
