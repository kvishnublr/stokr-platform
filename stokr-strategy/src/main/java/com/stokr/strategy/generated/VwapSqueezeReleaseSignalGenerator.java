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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VWAP_SQUEEZE — VWAP Band Squeeze Release
 *
 * EDGE: On flat/sideways periods, smart money quietly accumulates within a
 * tight range near VWAP (keeps price compressed — "squeeze"). When their
 * order is complete, a single large breakout candle fires with 3x volume
 * and other algos pile in. The compressed range BEFORE the breakout is
 * the actual signal — not the breakout itself.
 *
 * Works on BOTH trending AND flat days. On flat days it's the primary
 * signal; on trending days it fires at consolidation breakpoints.
 *
 * CONDITIONS:
 *   1. Last 20 1m closes ALL within ±0.15% of their running VWAP (squeeze)
 *   2. Average volume during squeeze < 0.85x session average (quiet accumulation)
 *   3. Breakout candle: body > 0.20%, volume > 2.5x prev-5 avg
 *   4. Volume accelerating: breakout bar > prior bar > bar before that
 *   5. Breakout direction aligns with VWAP slope
 *   6. OBI imbalance confirms direction
 *   7. Time: 09:30 – 14:45 IST
 *
 * EXIT:
 *   Target = 3× squeeze band from entry (0.45%)
 *   SL     = 0.15% — back into squeeze = invalidated
 *   R:R    = 3:1 (only need 25% win rate to break even)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
    strategyKey  = "VWAP_SQUEEZE",
    assetClass   = "EQUITY",
    segment      = "NSE",
    exchange     = "NSE",
    timeframe    = "1m"
)
public class VwapSqueezeReleaseSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME      = "1m";
    private static final int    BARS_FETCH     = 180;
    private static final int    MIN_BARS       = 30;
    // 15 (was 20): requiring 20 consecutive bars pinned to a moving VWAP almost
    // never occurs on liquid Nifty names — a 15-bar squeeze is still a genuine
    // compression but found several times per session.
    private static final int    SQUEEZE_BARS   = 15;

    private final StrategyGeneratorIntegrityGate integrityGate;
    private final OrderBookPressureTracker        pressureTracker;

    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.vwapsqueeze.squeeze-band-pct:0.0025}")
    private double squeezeBandPct;

    @Value("${stokr.strategy.vwapsqueeze.squeeze-vol-ratio:0.90}")
    private double squeezeVolRatio;

    @Value("${stokr.strategy.vwapsqueeze.breakout-body-pct:0.002}")
    private double breakoutBodyPct;

    @Value("${stokr.strategy.vwapsqueeze.breakout-vol-multiple:1.8}")
    private double breakoutVolMultiple;

    @Value("${stokr.strategy.vwapsqueeze.target-mult:3.0}")
    private double targetMult;

    // Stop at the squeeze boundary (0.25%), not 0.15%. A stop INSIDE the squeeze
    // band gets tagged by the same compression noise that defines the setup; the
    // breakout is only invalidated once price falls back through the band edge.
    // target = 0.25% x 3 = 0.75%, risk = 0.25% → 3:1 (break-even 25%).
    @Value("${stokr.strategy.vwapsqueeze.sl-pct:0.0025}")
    private double slPct;

    @Value("${stokr.strategy.vwapsqueeze.cooldown-seconds:1200}")
    private int cooldownSeconds;

    @Override
    public String key() { return "VWAP_SQUEEZE"; }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        Instant asOf  = context.asOf() != null ? context.asOf() : Instant.now();

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) return hold(context);

        // 1. SESSION: 09:30 – 14:45 IST
        LocalTime lt = asOf.atZone(zone).toLocalTime();
        if (lt.isBefore(LocalTime.of(9, 30)) || lt.isAfter(LocalTime.of(14, 45))) {
            return hold(context);
        }

        // 2. LOAD SESSION BARS
        var barsOpt = integrityGate.sessionBarsWithoutLookback(key(), symbol, TIMEFRAME, BARS_FETCH, context);
        if (barsOpt.isEmpty()) return hold(context);
        List<MarketdataCandle> bars = barsOpt.get();
        if (bars.size() < MIN_BARS) return hold(context);

        // 3. FIND SESSION START + COMPUTE RUNNING VWAP
        int sessionStart = findSessionStart(bars);
        if (sessionStart < 0) sessionStart = 0;
        int n = bars.size();
        int sessionLen = n - sessionStart;
        if (sessionLen < MIN_BARS) return hold(context);

        double[] vwap   = new double[sessionLen];
        double[] close  = new double[sessionLen];
        double[] vol    = new double[sessionLen];
        double   cumPV  = 0, cumVol = 0;

        for (int i = 0; i < sessionLen; i++) {
            MarketdataCandle b = bars.get(sessionStart + i);
            double h = toDouble(b.getHighPrice());
            double l = toDouble(b.getLowPrice());
            double c = toDouble(b.getClosePrice());
            double v = toDouble(b.getVolume());
            if (h <= 0 || l <= 0 || c <= 0) { close[i] = c; vol[i] = v; continue; }
            double tp = (h + l + c) / 3.0;
            cumPV  += tp * v;
            cumVol += v;
            vwap[i]  = cumVol > 0 ? cumPV / cumVol : tp;
            close[i] = c;
            vol[i]   = v;
        }

        // last index in session arrays
        int last = sessionLen - 1;
        if (last < SQUEEZE_BARS + 2) return hold(context);

        // 4. CHECK SQUEEZE: bars [last-SQUEEZE_BARS .. last-1] all within band
        // (current bar = last, which is the potential breakout)
        int squeezeStart = last - SQUEEZE_BARS;
        boolean isSqueeze = true;
        double  squeezeVol = 0;
        for (int i = squeezeStart; i < last; i++) {
            if (vwap[i] <= 0) { isSqueeze = false; break; }
            double distPct = Math.abs(close[i] - vwap[i]) / vwap[i];
            if (distPct > squeezeBandPct) { isSqueeze = false; break; }
            squeezeVol += vol[i];
        }
        if (!isSqueeze) return hold(context);

        // 5. SQUEEZE VOLUME < session average (quiet phase)
        double sessionAvgVol = cumVol / sessionLen;
        double squeezeAvgVol = squeezeVol / SQUEEZE_BARS;
        if (sessionAvgVol > 0 && squeezeAvgVol > squeezeVolRatio * sessionAvgVol) {
            return hold(context); // not a quiet squeeze
        }

        // 6. BREAKOUT CANDLE (current bar = last)
        MarketdataCandle curBar  = bars.get(n - 1);
        MarketdataCandle prevBar = bars.get(n - 2);
        MarketdataCandle prev2   = bars.size() > 2 ? bars.get(n - 3) : null;

        double curO  = toDouble(curBar.getOpenPrice());
        double curC  = toDouble(curBar.getClosePrice());
        double curV  = toDouble(curBar.getVolume());
        double prevV = toDouble(prevBar.getVolume());
        double prev2V = prev2 != null ? toDouble(prev2.getVolume()) : 0;

        double bodyPct = Math.abs(curC - curO) / curO;
        if (bodyPct < breakoutBodyPct) return hold(context);

        // Prev-5 volume average for breakout multiplier check
        double sum5 = 0; int cnt5 = 0;
        for (int i = Math.max(0, n - 6); i < n - 1; i++) {
            sum5 += toDouble(bars.get(i).getVolume()); cnt5++;
        }
        double avg5Vol = cnt5 > 0 ? sum5 / cnt5 : 1;
        if (avg5Vol > 0 && curV / avg5Vol < breakoutVolMultiple) return hold(context);

        // 7. VOLUME EXPANSION: breakout bar must be heavier than the prior bar.
        //    (Dropped the additional prev>prev2 requirement — a 3-bar monotonic
        //    ramp almost never coincides with a fresh squeeze release and made the
        //    strategy fire <1/day. Breakout-vs-prev + the 2x prev-5 gate suffice.)
        if (prevV > 0 && curV <= prevV) return hold(context);

        // 8. DIRECTION — must match VWAP slope
        double vwapSlope = last >= 5 ? (vwap[last - 1] - vwap[last - 6]) / vwap[last - 6] : 0;
        boolean isBuy = curC > curO;
        if (isBuy  && vwapSlope < -0.0005) return hold(context); // VWAP declining — no BUY
        if (!isBuy && vwapSlope >  0.0005) return hold(context); // VWAP rising — no SELL

        // 9. OBI CONFIRMATION — live book or OHLCV proxy (works in backtest).
        PressureSnapshot snap = pressureTracker.getSnapshot(symbol);
        double obi = resolvePressure(snap != null ? snap.imbalanceRatio() : null, bars, 5);
        if (isBuy  && obi < 0.45) return hold(context);
        if (!isBuy && obi > 0.55) return hold(context);

        // 10. COOLDOWN (guard backtest time-travel: ignore stale cooldown from a
        //    prior run when replay jumps backwards — see ADV_CASH for details)
        Instant lastEmit = lastEmitBySymbol.get(symbol);
        if (lastEmit != null) {
            long sinceLast = Duration.between(lastEmit, asOf).getSeconds();
            if (sinceLast >= 0 && sinceLast < cooldownSeconds) {
                return hold(context);
            }
        }

        // 11. SIGNAL
        double entry = curC;
        double targetPrice, stopPrice;
        SignalType sigType;
        if (isBuy) {
            sigType     = SignalType.BUY;
            targetPrice = entry * (1.0 + squeezeBandPct * targetMult);
            stopPrice   = entry * (1.0 - slPct);
        } else {
            sigType     = SignalType.SELL;
            targetPrice = entry * (1.0 - squeezeBandPct * targetMult);
            stopPrice   = entry * (1.0 + slPct);
        }

        double risk   = Math.abs(entry - stopPrice);
        double reward = Math.abs(targetPrice - entry);
        if (risk <= 0 || reward / risk < 2.5) return hold(context);

        lastEmitBySymbol.put(symbol, asOf);

        String reason = String.format(
            "VWAP_SQUEEZE %s: body=%.3f%% vol=%.1fx avgSqzVol=%.0f avgSessVol=%.0f vwapSlope=%.4f%% " +
            "entry=%.2f sl=%.2f target=%.2f rr=%.1f",
            sigType, bodyPct * 100.0, avg5Vol > 0 ? curV / avg5Vol : 0,
            squeezeAvgVol, sessionAvgVol, vwapSlope * 100.0,
            entry, stopPrice, targetPrice, reward / risk
        );
        log.info("vwap_squeeze.signal symbol={} {}", symbol, reason);

        return new StrategySignal(sigType, symbol, BigDecimal.ONE, reason,
                BigDecimal.valueOf(entry).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(stopPrice).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(targetPrice).setScale(2, RoundingMode.HALF_UP));
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
