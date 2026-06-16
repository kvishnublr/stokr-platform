package com.stokr.strategy.service;

import com.stokr.common.events.SignalOutcomeEvents;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.ExitCategory;
import com.stokr.strategy.lifecycle.ExitDecision;
import com.stokr.strategy.lifecycle.PressureExitTrigger;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryService;
import com.stokr.strategy.lifecycle.StrategyLifecycleProfile;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Objects;

/**
 * Lifecycle-aware exit engine with strategy-specific minimum hold times.
 *
 * Exit hierarchy (counterfactual replay of May-Jun 2026 signals showed order-book noise
 * triggers were cutting winners at 10-25% of target progress while winners resolve in
 * ~5-7 min median and losers bleed for 20-31 min):
 *   1. HARD_STOP ??? SL breach, always, immediately.
 *   2. FEED_PROTECTION ??? freeze evaluation while feed is stale; force-exit only after a
 *      prolonged stall (stale feed is our data problem, not a market move against us).
 *   3. LIQUIDITY_PROTECTION ??? extreme bar range / volume vacuum, after emergency min hold.
 *   4. TRAILING_BREAKEVEN ??? price returned to entry after reaching a large share of target.
 *   5. PROGRESS_SCRATCH (TIME_EXIT) ??? trade has not covered the minimum share of target
 *      distance within the strategy's time stop; cuts slow bleeders without touching the
 *      fast winners.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PressureSmartExitService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final int MAX_SIGNALS_PER_SCAN = 100;
    private static final int LOOKBACK_HOURS = 72;

    private final StrategySignalRepository signalRepository;
    private final OrderBookPressureTracker pressureTracker;
    private final MarketDataQueryService marketDataQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyExitTelemetryService exitTelemetryService;
    private final SignalOutcomeTrackerService signalOutcomeTrackerService;
    private final InstrumentNormalizationService instrumentNormalizationService;

    @Value("${stokr.strategy.smart-exit.trailing-mfe-ratio:0.60}")
    private double trailingMfeRatio;

    @Value("${stokr.strategy.smart-exit.min-progress-pct:40}")
    private double minProgressPct;

    @Value("${stokr.strategy.smart-exit.enabled:true}")
    private boolean enabled;

    /**
     * Emergency liquidity exit: same bar-range proxy but higher threshold so normal 1m volatility
     * does not instantly close every signal before target/SL tracking can run.
     */
    @Value("${stokr.strategy.lifecycle.emergency.bar-range-pct:3.5}")
    private double emergencyBarRangePct;

    /** Beyond this staleness, exit evaluation is frozen ??? data is unreliable, do not act on it. */
    @Value("${stokr.strategy.lifecycle.emergency.candle-stale-seconds:180}")
    private long emergencyCandleStaleSeconds;

    /** Only after this prolonged stall do we force-close the position for feed protection. */
    @Value("${stokr.strategy.lifecycle.emergency.force-exit-stale-seconds:600}")
    private long forceExitStaleSeconds;

    @Value("${stokr.strategy.lifecycle.emergency.volume-vacuum-ratio:0.05}")
    private double emergencyVolumeVacuumRatio;

    @Value("${stokr.strategy.lifecycle.emergency.min-hold-seconds:300}")
    private long emergencyMinHoldSeconds;

    @Value("${stokr.strategy.lifecycle.emergency.current-bar-min-age-seconds:55}")
    private long emergencyCurrentBarMinAgeSeconds;

    @Scheduled(fixedDelayString = "${stokr.strategy.smart-exit.interval-ms:15000}")
    @Transactional
    public void monitorAndExit() {
        if (!enabled) {
            return;
        }

        Instant since = Instant.now().minus(LOOKBACK_HOURS, ChronoUnit.HOURS);
        List<StrategySignalEntity> running = signalRepository.findRunningSignalsSince(
                since, PageRequest.of(0, MAX_SIGNALS_PER_SCAN));
        if (running.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        int exited = 0;

        for (StrategySignalEntity sig : running) {
            if (!isEligible(sig)) {
                continue;
            }
            try {
                ExitDecision decision = evaluateExit(sig, now);
                if (decision != null) {
                    applyExit(sig, decision, now);
                    exited++;
                    broadcastOutcomeChange(sig, decision);
                }
            } catch (Exception ex) {
                log.debug("smart_exit.eval_error signalId={} {}", sig.getId(), ex.getMessage());
            }
        }

        if (exited > 0) {
            log.info("smart_exit.scan exited={}", exited);
        }
    }

    private boolean isEligible(StrategySignalEntity sig) {
        if (ExitCategory.isTerminalOutcome(sig.getOutcomeStatus())) {
            return false;
        }
        if (sig.getStrategyName() == null || sig.getEntryReferencePrice() == null
                || sig.getEntryReferencePrice().signum() <= 0
                || sig.getStopPrice() == null || sig.getTargetPrice() == null) {
            return false;
        }
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy(sig.getStrategyName());
        return profile.pressureExitEnabled();
    }

    private ExitDecision evaluateExit(StrategySignalEntity sig, Instant now) {
        StrategyLifecycleProfile profile = StrategyLifecycleProfile.forStrategy(sig.getStrategyName());
        long holdSeconds = StrategyExitTelemetryService.holdSeconds(sig, now);
        boolean minHoldSatisfied = holdSeconds >= profile.minHoldSeconds();
        boolean emergencyMinHoldSatisfied = holdSeconds >= Math.max(profile.minHoldSeconds(), emergencyMinHoldSeconds);

        signalOutcomeTrackerService.evaluateSingleSignal(sig, now);
        if (ExitCategory.isTerminalOutcome(sig.getOutcomeStatus()) && !Objects.equals(sig.getOutcomeStatus(), STATUS_RUNNING)) {
            return null;
        }

        ExitContext ctx = buildContext(sig, now, profile);
        if (ctx == null) {
            return null;
        }

        ExitDecision emergency = evaluateEmergencyExit(ctx);
        if (emergency != null) {
            if ((emergency.category() == ExitCategory.HARD_STOP || emergency.category() == ExitCategory.FEED_PROTECTION)) {
                return emergency;
            }
            if (!emergencyMinHoldSatisfied) {
                return null;
            }
            return emergency;
        }

        // Feed stale but not long enough to force-exit: freeze ??? do not evaluate trailing or
        // time exits against unreliable prices. Target/SL tracking resumes when the feed recovers.
        if (ctx.candleStaleSeconds() > emergencyCandleStaleSeconds) {
            log.warn("smart_exit.feed_stale_freeze strategy={} symbol={} staleSec={} forceExitSec={}",
                    sig.getStrategyName(), sig.getSymbol(), ctx.candleStaleSeconds(), forceExitStaleSeconds);
            return null;
        }

        if (!minHoldSatisfied) {
            log.debug("smart_exit.min_hold_blocked strategy={} symbol={} holdSec={} minHoldSec={}",
                    sig.getStrategyName(), sig.getSymbol(), holdSeconds, profile.minHoldSeconds());
            return null;
        }

        return evaluateTrailingAndTimeExit(ctx);
    }

    private ExitDecision evaluateEmergencyExit(ExitContext ctx) {
        if (ctx.hardSlBreached()) {
            return ExitDecision.emergency(
                    ExitCategory.HARD_STOP,
                    String.format("HARD_SL_BREACH: price=%.4f sl=%.4f direction=%s",
                            ctx.currentPrice(), ctx.slPrice(), ctx.isBuy() ? "BUY" : "SELL"));
        }

        if (ctx.candleStaleSeconds() > forceExitStaleSeconds) {
            return ExitDecision.emergency(
                    ExitCategory.FEED_PROTECTION,
                    String.format("FEED_STALE_PROLONGED: latestBarAgeSec=%d thresholdSec=%d",
                            ctx.candleStaleSeconds(), forceExitStaleSeconds));
        }

        if (ctx.spreadPct() >= emergencyBarRangePct) {
            return ExitDecision.emergency(
                    ExitCategory.LIQUIDITY_PROTECTION,
                    String.format("BAR_RANGE_SPIKE: rangePct=%.3f thresholdPct=%.3f barRange=%.4f",
                            ctx.spreadPct(), emergencyBarRangePct, ctx.barRange()));
        }

        if (ctx.volumeVacuum()) {
            return ExitDecision.emergency(
                    ExitCategory.LIQUIDITY_PROTECTION,
                    String.format("VOLUME_VACUUM: currentVol=%.0f expectedVol=%.0f avgVol=%.0f barAgeSec=%d ratioThreshold=%.2f",
                            ctx.currentVolume(), ctx.expectedCurrentVolume(), ctx.avgVolume(),
                            ctx.currentBarAgeSeconds(), emergencyVolumeVacuumRatio));
        }

        return null;
    }

    private ExitDecision evaluateTrailingAndTimeExit(ExitContext ctx) {
        StrategySignalEntity sig = ctx.signal();

        // ?????? PROFIT TRAILING (trend strategies only ??? opt-in via lifecycle profile) ??????
        // Once the trade has run trailArmPct in our favour, ride it but exit if price gives
        // back trailGiveBackPct (of entry) from the peak. This captures trend-day tails
        // while locking gains ??? the only exit that beats transaction costs for breakouts.
        if (ctx.profile().profitTrailingEnabled()) {
            ExitDecision trail = evaluateProfitTrailing(ctx);
            if (trail != null) {
                return trail;
            }
        }

        if (ctx.mfePctOfTarget() >= trailingMfeRatio * 100 && ctx.backAtEntry()) {
            String reason = String.format(
                    "TRAILING_BREAKEVEN: mfePctOfTarget=%.1f threshold=%.1f priceReturnedToEntry=true",
                    ctx.mfePctOfTarget(), trailingMfeRatio * 100);
            log.warn("smart_exit.trailing_breakeven strategy={} symbol={} reason={}",
                    sig.getStrategyName(), sig.getSymbol(), reason);
            return ExitDecision.pressure(PressureExitTrigger.TRAILING_BREAKEVEN, reason, false);
        }

        // Progress scratch: winners on the kept strategies reach target in ~5-7 min median;
        // a trade that has not covered minProgressPct of the target distance by the time stop
        // is a slow bleeder ??? cut it instead of waiting for the full SL.
        int timeStopMinutes = ctx.profile().timeStopMinutes();
        if (ctx.ageMinutes() >= timeStopMinutes && ctx.currentProgress() < minProgressPct) {
            String reason = String.format(
                    "PROGRESS_SCRATCH: ageMin=%d timeStopMin=%d progress=%.1f minProgressPct=%.1f",
                    ctx.ageMinutes(), timeStopMinutes, ctx.currentProgress(), minProgressPct);
            log.info("smart_exit.time_exit strategy={} symbol={} {}", sig.getStrategyName(), sig.getSymbol(), reason);
            return ExitDecision.timeExit(reason);
        }

        return null;
    }

    /**
     * Profit-trailing stop (opt-in per strategy). Uses the tracked max-favorable-excursion
     * (MFE, a price distance) as the "peak". Once the trade has run {@code trailArmPct} of
     * entry in our favour, exit if it has retraced {@code trailGiveBackPct} of entry from
     * that peak ??? locking gains while letting trend tails extend.
     */
    private ExitDecision evaluateProfitTrailing(ExitContext ctx) {
        StrategySignalEntity sig = ctx.signal();
        if (sig.getEntryReferencePrice() == null) {
            return null;
        }
        double entry = sig.getEntryReferencePrice().doubleValue();
        if (entry <= 0) {
            return null;
        }
        double mfe = sig.getMaxFavorableExcursion() != null
                ? sig.getMaxFavorableExcursion().doubleValue()
                : 0.0;
        double curFav = ctx.isBuy() ? (ctx.currentPrice() - entry) : (entry - ctx.currentPrice());
        double armDist = entry * ctx.profile().trailArmPct() / 100.0;
        double giveBackDist = entry * ctx.profile().trailGiveBackPct() / 100.0;
        // Only trail once the move is armed and we are still in profit.
        if (mfe < armDist || curFav <= 0 || giveBackDist <= 0) {
            return null;
        }
        double giveBack = mfe - curFav; // retrace from the peak, in price units
        if (giveBack >= giveBackDist) {
            String reason = String.format(
                    "TRAILING_PROFIT: mfe=%.4f curFav=%.4f giveBack=%.4f trailDist=%.4f armDist=%.4f",
                    mfe, curFav, giveBack, giveBackDist, armDist);
            log.info("smart_exit.trailing_profit strategy={} symbol={} {}",
                    sig.getStrategyName(), sig.getSymbol(), reason);
            return ExitDecision.pressure(PressureExitTrigger.TRAILING_PROFIT, reason, false);
        }
        return null;
    }

    private ExitContext buildContext(StrategySignalEntity sig, Instant now, StrategyLifecycleProfile profile) {
        String symbol = sig.getSymbol();
        boolean isBuy = sig.getSignalType() == SignalType.BUY;
        BigDecimal entry = sig.getEntryReferencePrice();
        BigDecimal target = sig.getTargetPrice();
        BigDecimal sl = sig.getStopPrice();

        String normalizedSymbol = instrumentNormalizationService.normalizeForMarketData(symbol);
        if (normalizedSymbol == null) {
            return null;
        }

        List<MarketdataCandle> recentBars = marketDataQueryService.lastBarsAsc(normalizedSymbol, "1m", 12);
        if (recentBars.isEmpty()) {
            return null;
        }

        MarketdataCandle lastBar = recentBars.get(recentBars.size() - 1);
        if (lastBar.getClosePrice() == null) {
            return null;
        }

        double currentPrice = lastBar.getClosePrice().doubleValue();
        double entryPrice = entry.doubleValue();
        double targetPrice = target.doubleValue();
        double slPrice = sl.doubleValue();

        double pnlPct = isBuy
                ? (currentPrice - entryPrice) / entryPrice * 100
                : (entryPrice - currentPrice) / entryPrice * 100;

        double targetDistance = Math.abs(targetPrice - entryPrice);
        double currentProgress = targetDistance > 0
                ? Math.abs(currentPrice - entryPrice) / targetDistance * 100
                : 0;
        if (pnlPct < 0) {
            currentProgress = -currentProgress;
        }

        double mfe = sig.getMaxFavorableExcursion() != null
                ? sig.getMaxFavorableExcursion().doubleValue()
                : 0;
        double mfePctOfTarget = targetDistance > 0 ? mfe / targetDistance * 100 : 0;

        Instant signalTime = StrategyExitTelemetryService.resolveEntryTime(sig);
        long ageMinutes = ChronoUnit.MINUTES.between(signalTime, now);

        double high = lastBar.getHighPrice() != null ? lastBar.getHighPrice().doubleValue() : currentPrice;
        double low = lastBar.getLowPrice() != null ? lastBar.getLowPrice().doubleValue() : currentPrice;
        double barRange = high - low;
        double mid = (high + low) / 2.0;
        double spreadPct = mid > 0 ? barRange / mid * 100 : 0;

        long candleStaleSeconds = 0;
        long currentBarAgeSeconds = 0;
        if (lastBar.getOpenTime() != null) {
            candleStaleSeconds = Duration.between(lastBar.getOpenTime(), now).getSeconds();
            currentBarAgeSeconds = Math.max(0, candleStaleSeconds % 60);
        }

        double currentVolume = lastBar.getVolume() != null ? lastBar.getVolume().doubleValue() : 0;
        double avgVolume = 0;
        int volCount = 0;
        for (int i = Math.max(0, recentBars.size() - 11); i < recentBars.size() - 1; i++) {
            if (recentBars.get(i).getVolume() != null && recentBars.get(i).getVolume().doubleValue() > 0) {
                avgVolume += recentBars.get(i).getVolume().doubleValue();
                volCount++;
            }
        }
        avgVolume = volCount > 0 ? avgVolume / volCount : 0;

        boolean hardSlBreached = isBuy
                ? low <= slPrice
                : high >= slPrice;

        boolean backAtEntry = isBuy
                ? currentPrice <= entryPrice * 1.001
                : currentPrice >= entryPrice * 0.999;

        double progressFactor = Math.min(1d, Math.max(0d, currentBarAgeSeconds / 60d));
        double expectedCurrentVolume = avgVolume * progressFactor;
        boolean volumeVacuum = currentBarAgeSeconds >= emergencyCurrentBarMinAgeSeconds
                && expectedCurrentVolume > 0
                && currentVolume / expectedCurrentVolume < emergencyVolumeVacuumRatio;

        return new ExitContext(
                sig, profile, isBuy, recentBars, lastBar,
                currentPrice, slPrice, pnlPct, currentProgress, mfePctOfTarget,
                ageMinutes, spreadPct, barRange, candleStaleSeconds,
                currentVolume, avgVolume, expectedCurrentVolume, currentBarAgeSeconds,
                hardSlBreached, backAtEntry, volumeVacuum);
    }

    private void applyExit(StrategySignalEntity sig, ExitDecision decision, Instant now) {
        String symbol = sig.getSymbol();
        boolean isBuy = sig.getSignalType() == SignalType.BUY;
        BigDecimal entry = sig.getEntryReferencePrice();
        BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;

        BigDecimal exitPrice = resolveExitPrice(sig, decision, now, entry);
        BigDecimal pnl = isBuy
                ? exitPrice.subtract(entry).multiply(qty)
                : entry.subtract(exitPrice).multiply(qty);

        SignalLifecycleService.updateOutcome(sig, decision.category().outcomeStatus());
        sig.setOutcomeTime(now);
        sig.setExitPrice(exitPrice);
        sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        sig.setUnrealizedPnl(null);
        sig.setExpiryReason(decision.category().name() + ": " + decision.reason());

        if (decision.category() == ExitCategory.HARD_STOP) {
            sig.setHitStoploss(true);
        }

        if (sig.getEntryPrice() == null) {
            sig.setEntryPrice(entry);
        }

        BigDecimal pressureScore = null;
        String normalizedSymbol = instrumentNormalizationService.normalizeForMarketData(symbol);
        PressureSnapshot snapshot = pressureTracker.getSnapshot(normalizedSymbol != null ? normalizedSymbol : symbol);
        if (snapshot != null) {
            pressureScore = BigDecimal.valueOf(snapshot.imbalanceRatio()).setScale(4, RoundingMode.HALF_UP);
        }

        exitTelemetryService.recordExit(
                sig,
                decision.category(),
                decision.reason(),
                now,
                pressureScore,
                decision.pressureTrigger(),
                decision.minHoldBypassed());

        log.info("smart_exit.closed strategy={} symbol={} category={} entry={} exit={} pnl={} reason={}",
                sig.getStrategyName(), symbol, decision.category().name(),
                entry, exitPrice, pnl.setScale(2, RoundingMode.HALF_UP), decision.reason());
    }

    private BigDecimal resolveExitPrice(
            StrategySignalEntity sig,
            ExitDecision decision,
            Instant now,
            BigDecimal entry) {
        if (decision.category() == ExitCategory.HARD_STOP && sig.getStopPrice() != null) {
            return sig.getStopPrice();
        }

        String normalizedSymbol = instrumentNormalizationService.normalizeForMarketData(sig.getSymbol());
        if (normalizedSymbol == null) {
            return entry;
        }
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(normalizedSymbol, "1m", 1);
        if (!bars.isEmpty() && bars.get(bars.size() - 1).getClosePrice() != null) {
            return bars.get(bars.size() - 1).getClosePrice();
        }
        return entry;
    }

    private void broadcastOutcomeChange(StrategySignalEntity sig, ExitDecision decision) {
        try {
            SignalPublishedEvent signalEvt = new SignalPublishedEvent(
                    sig.getId(), sig.getUserId(), sig.getSymbol(), sig.getStrategyName());
            eventPublisher.publishEvent(signalEvt);
            eventPublisher.publishEvent(SignalOutcomeEvents.outcome(
                    sig.getId(),
                    sig.getSymbol(),
                    sig.getStrategyName(),
                    sig.getOutcomeStatus(),
                    sig.getUserId(),
                    sig.getRealizedPnl() != null ? sig.getRealizedPnl().toPlainString() : "0",
                    sig.getExpiryReason(),
                    decision.category().name()
            ));
        } catch (Exception ex) {
            log.debug("smart_exit.broadcast_error signalId={}", sig.getId(), ex);
        }
    }

    private record ExitContext(
            StrategySignalEntity signal,
            StrategyLifecycleProfile profile,
            boolean isBuy,
            List<MarketdataCandle> recentBars,
            MarketdataCandle lastBar,
            double currentPrice,
            double slPrice,
            double pnlPct,
            double currentProgress,
            double mfePctOfTarget,
            long ageMinutes,
            double spreadPct,
            double barRange,
            long candleStaleSeconds,
            double currentVolume,
            double avgVolume,
            double expectedCurrentVolume,
            long currentBarAgeSeconds,
            boolean hardSlBreached,
            boolean backAtEntry,
            boolean volumeVacuum) {
    }
}
