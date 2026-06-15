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

/**
 * OPENING_RANGE_BREAKOUT — Opening Range Breakout (trend strategy).
 *
 * <p>EDGE: The first 15 minutes (09:15–09:30) set the day's opening range. A
 * volume-confirmed break of that range tends to run on trend days. Unlike the
 * reversion strategies, ORB has a real, backtest-validated edge — but ONLY with a
 * profit-trailing exit: research over May–Jun 2026 (20 large-caps) showed ~+4%/month
 * with ~1% drawdown when trailing, vs NEGATIVE with a fixed target. The trailing is
 * configured in {@code StrategyLifecycleProfile} (profitTrailingEnabled=true).</p>
 *
 * <p>CONDITIONS (one entry per symbol per day):</p>
 * <ol>
 *   <li>Compute opening range (first {@code orMinutes} bars): ORH, ORL, avg volume.</li>
 *   <li>After the OR window, take the FIRST bar that CLOSES beyond ORH (long) / ORL
 *       (short) with volume ≥ {@code volumeMultiple} × OR-average volume.</li>
 *   <li>Initial stop = opposite OR edge, capped at {@code maxStopPct}.</li>
 *   <li>Target = entry ± {@code targetRMultiple} × risk (far — the trailing exit
 *       handles the real exit; the wide target keeps MFE/progress meaningful).</li>
 *   <li>Entry session 09:30–14:30 IST (skip the close, give the trail room to run).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "OPENING_RANGE_BREAKOUT",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class OpeningRangeBreakoutSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME  = "1m";
    private static final int    BARS_FETCH = 360;   // whole session
    private static final int    MIN_BARS   = 20;

    private final StrategyGeneratorIntegrityGate integrityGate;

    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.orb.or-minutes:15}")
    private int orMinutes;

    @Value("${stokr.strategy.orb.volume-multiple:2.0}")
    private double volumeMultiple;

    @Value("${stokr.strategy.orb.max-stop-pct:0.006}")
    private double maxStopPct;

    @Value("${stokr.strategy.orb.target-r-multiple:3.0}")
    private double targetRMultiple;

    @Value("${stokr.strategy.orb.cooldown-seconds:21600}")  // 6h → one entry per symbol per day
    private int cooldownSeconds;

    @Override
    public String key() { return "OPENING_RANGE_BREAKOUT"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf  = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) return hold(context);

        // 1. ENTRY SESSION: 09:30 – 12:30 IST (after the OR window, before lunch fade)
        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(LocalTime.of(9, 30)) || lt.isAfter(LocalTime.of(12, 30))) {
            return hold(context);
        }

        // 2. ONE ENTRY PER SYMBOL PER DAY (time-travel-safe cooldown)
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null) {
            long sinceLast = Duration.between(lastEmit, asOf).getSeconds();
            if (sinceLast >= 0 && sinceLast < cooldownSeconds) {
                return hold(context);
            }
        }

        // 3. LOAD SESSION BARS
        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < MIN_BARS) return hold(context);

        int sessionStart = findSessionStart(bars);
        if (sessionStart < 0) sessionStart = 0;
        int n = bars.size();
        int sessionLen = n - sessionStart;
        if (sessionLen < orMinutes + 2) return hold(context);

        // 4. OPENING RANGE — first orMinutes bars of the session
        double orh = Double.NEGATIVE_INFINITY, orl = Double.POSITIVE_INFINITY, orVolSum = 0;
        for (int i = 0; i < orMinutes; i++) {
            MarketdataCandle b = bars.get(sessionStart + i);
            double h = toD(b.getHighPrice());
            double l = toD(b.getLowPrice());
            if (h <= 0 || l <= 0) return hold(context);
            orh = Math.max(orh, h);
            orl = Math.min(orl, l);
            orVolSum += toD(b.getVolume());
        }
        double orAvgVol = orVolSum / orMinutes;
        if (orh <= 0 || orl <= 0 || orAvgVol <= 0) return hold(context);

        // 5. BREAKOUT on the current (latest) bar
        MarketdataCandle cur = bars.get(n - 1);
        double curClose = toD(cur.getClosePrice());
        double curVol   = toD(cur.getVolume());
        if (curClose <= 0) return hold(context);

        boolean longBreak  = curClose > orh && curVol >= volumeMultiple * orAvgVol;
        boolean shortBreak = curClose < orl && curVol >= volumeMultiple * orAvgVol;
        if (!longBreak && !shortBreak) return hold(context);

        boolean isLong = longBreak;
        double entry = curClose;

        // 6. INITIAL STOP — opposite OR edge, capped at maxStopPct
        double stop;
        if (isLong) {
            stop = Math.max(orl, entry * (1.0 - maxStopPct));
        } else {
            stop = Math.min(orh, entry * (1.0 + maxStopPct));
        }
        double risk = Math.abs(entry - stop);
        if (risk <= 0 || risk / entry < 0.0008) return hold(context);   // too-tight stop → skip

        // 7. FAR TARGET — trailing exit does the real work; target keeps MFE meaningful
        double target = isLong ? entry + targetRMultiple * risk : entry - targetRMultiple * risk;

        lastEmitBySymbol.put(symbol, asOf);

        SignalType sigType = isLong ? SignalType.BUY : SignalType.SELL;
        String reason = String.format(
            "ORB %s: orh=%.2f orl=%.2f orVol=%.0f curVol=%.0f volX=%.1f entry=%.2f sl=%.2f target=%.2f riskPct=%.2f%%",
            sigType, orh, orl, orAvgVol, curVol, curVol / orAvgVol,
            entry, stop, target, risk / entry * 100.0);
        log.info("orb.signal symbol={} {}", symbol, reason);

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

    private static double toD(BigDecimal v) { return v == null ? 0.0 : v.doubleValue(); }
}
