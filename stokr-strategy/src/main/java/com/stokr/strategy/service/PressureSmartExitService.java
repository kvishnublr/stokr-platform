package com.stokr.strategy.service;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureAnalysis;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * Lifecycle-aware pressure exit engine with strategy-specific minimum hold times.
 * PRESSURE_EXIT is blocked until min hold unless an emergency condition fires.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PressureSmartExitService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final int MAX_SIGNALS_PER_SCAN = 100;
    private static final int LOOKBACK_HOURS = 8;

    private final StrategySignalRepository signalRepository;
    private final OrderBookPressureTracker pressureTracker;
    private final MarketDataQueryService marketDataQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final StrategyExitTelemetryService exitTelemetryService;
    private final SignalOutcomeTrackerService signalOutcomeTrackerService;
    private final InstrumentNormalizationService instrumentNormalizationService;

    @Value("${stokr.strategy.smart-exit.pressure-reversal-buy:0.43}")
    private double pressureReversalBuyThreshold;

    @Value("${stokr.strategy.smart-exit.pressure-reversal-sell:0.57}")
    private double pressureReversalSellThreshold;

    @Value("${stokr.strategy.smart-exit.exhaustion-consistency:0.35}")
    private double exhaustionConsistency;

    @Value("${stokr.strategy.smart-exit.pressure-lookback:60}")
    private int pressureLookback;

    @Value("${stokr.strategy.smart-exit.trailing-mfe-ratio:0.40}")
    private double trailingMfeRatio;

    @Value("${stokr.strategy.smart-exit.min-progress-pct:20}")
    private double minProgressPct;

    @Value("${stokr.strategy.smart-exit.counter-candle-body-ratio:0.55}")
    private double counterCandleBodyRatio;

    @Value("${stokr.strategy.smart-exit.enabled:true}")
    private boolean enabled;

    /** Min-hold pressure path: bar-range % vs mid on latest 1m candle (not bid-ask spread). */
    @Value("${stokr.strategy.lifecycle.emergency.spread-pct:0.50}")
    private double emergencySpreadPct;

    /**
     * Emergency liquidity exit: same bar-range proxy but higher threshold so normal 1m volatility
     * does not instantly close every signal before target/SL tracking can run.
     */
    @Value("${stokr.strategy.lifecycle.emergency.bar-range-pct:2.5}")
    private double emergencyBarRangePct;

    @Value("${stokr.strategy.lifecycle.emergency.candle-stale-seconds:120}")
    private long emergencyCandleStaleSeconds;

    @Value("${stokr.strategy.lifecycle.emergency.volume-vacuum-ratio:0.10}")
    private double emergencyVolumeVacuumRatio;

    @Value("${stokr.strategy.lifecycle.emergency.min-hold-seconds:180}")
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

        if (!minHoldSatisfied) {
            log.debug("smart_exit.min_hold_blocked strategy={} symbol={} holdSec={} minHoldSec={}",
                    sig.getStrategyName(), sig.getSymbol(), holdSeconds, profile.minHoldSeconds());
            return null;
        }

        return evaluatePressureAndTimeExit(ctx);
    }

    private ExitDecision evaluateEmergencyExit(ExitContext ctx) {
        if (ctx.hardSlBreached()) {
            return ExitDecision.emergency(
                    ExitCategory.HARD_STOP,
                    String.format("HARD_SL_BREACH: price=%.4f sl=%.4f direction=%s",
                            ctx.currentPrice(), ctx.slPrice(), ctx.isBuy() ? "BUY" : "SELL"));
        }

        if (ctx.candleStaleSeconds() > emergencyCandleStaleSeconds) {
            return ExitDecision.emergency(
                    ExitCategory.FEED_PROTECTION,
                    String.format("FEED_STALE: latestBarAgeSec=%d thresholdSec=%d",
                            ctx.candleStaleSeconds(), emergencyCandleStaleSeconds));
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

    private ExitDecision evaluatePressureAndTimeExit(ExitContext ctx) {
        StrategySignalEntity sig = ctx.signal();
        PressureSnapshot snapshot = ctx.snapshot();
        PressureAnalysis analysis = ctx.analysis();

        if (snapshot != null && analysis != null) {
            double ratio = snapshot.imbalanceRatio();

            if (ctx.isBuy() && ratio < pressureReversalBuyThreshold && ctx.currentProgress() < 70) {
                String reason = String.format(
                        "IMBALANCE_COLLAPSE: buy→sell ratio=%.2f threshold=%.2f progress=%.1f%%",
                        ratio, pressureReversalBuyThreshold, ctx.currentProgress());
                logPressureExit(sig, PressureExitTrigger.IMBALANCE_COLLAPSE, reason);
                return ExitDecision.pressure(PressureExitTrigger.IMBALANCE_COLLAPSE, reason, false);
            }
            if (!ctx.isBuy() && ratio > pressureReversalSellThreshold && ctx.currentProgress() < 70) {
                String reason = String.format(
                        "IMBALANCE_COLLAPSE: sell→buy ratio=%.2f threshold=%.2f progress=%.1f%%",
                        ratio, pressureReversalSellThreshold, ctx.currentProgress());
                logPressureExit(sig, PressureExitTrigger.IMBALANCE_COLLAPSE, reason);
                return ExitDecision.pressure(PressureExitTrigger.IMBALANCE_COLLAPSE, reason, false);
            }
        }

        if (analysis != null) {
            double consistency = analysis.pressureConsistency();
            if (consistency < exhaustionConsistency && ctx.currentProgress() < 50) {
                String reason = String.format(
                        "VOLUME_VACUUM: consistency=%.2f threshold=%.2f progress=%.1f%%",
                        consistency, exhaustionConsistency, ctx.currentProgress());
                logPressureExit(sig, PressureExitTrigger.VOLUME_VACUUM, reason);
                return ExitDecision.pressure(PressureExitTrigger.VOLUME_VACUUM, reason, false);
            }
        }

        if (ctx.mfePctOfTarget() >= trailingMfeRatio * 100 && ctx.backAtEntry()) {
            String reason = String.format(
                    "TRAILING_BREAKEVEN: mfePctOfTarget=%.1f threshold=%.1f priceReturnedToEntry=true",
                    ctx.mfePctOfTarget(), trailingMfeRatio * 100);
            logPressureExit(sig, PressureExitTrigger.TRAILING_BREAKEVEN, reason);
            return ExitDecision.pressure(PressureExitTrigger.TRAILING_BREAKEVEN, reason, false);
        }

        if (ctx.ageMinutes() >= 3 && ctx.recentBars().size() >= 2) {
            MarketdataCandle lastBar = ctx.lastBar();
            MarketdataCandle prevBar = ctx.recentBars().get(ctx.recentBars().size() - 2);
            if (isCounterCandle(lastBar, ctx.isBuy())) {
                if (isCounterCandle(prevBar, ctx.isBuy())) {
                    String reason = String.format(
                            "MOMENTUM_REVERSAL: consecutiveCounterBars=2 pnlPct=%.3f bodyRatioThreshold=%.2f",
                            ctx.pnlPct(), counterCandleBodyRatio);
                    logPressureExit(sig, PressureExitTrigger.MOMENTUM_REVERSAL, reason);
                    return ExitDecision.pressure(PressureExitTrigger.MOMENTUM_REVERSAL, reason, false);
                }
                if (ctx.pnlPct() <= 0.05) {
                    String reason = String.format(
                            "ADVERSE_VELOCITY: counterBar pnlPct=%.3f bodyRatioThreshold=%.2f",
                            ctx.pnlPct(), counterCandleBodyRatio);
                    logPressureExit(sig, PressureExitTrigger.ADVERSE_VELOCITY, reason);
                    return ExitDecision.pressure(PressureExitTrigger.ADVERSE_VELOCITY, reason, false);
                }
            }
        }

        if (ctx.spreadPct() >= emergencySpreadPct * 0.6 && ctx.pnlPct() < 0) {
            String reason = String.format(
                    "SPREAD_WIDENING: spreadPct=%.3f warnThreshold=%.3f pnlPct=%.3f",
                    ctx.spreadPct(), emergencySpreadPct * 0.6, ctx.pnlPct());
            logPressureExit(sig, PressureExitTrigger.SPREAD_WIDENING, reason);
            return ExitDecision.pressure(PressureExitTrigger.SPREAD_WIDENING, reason, false);
        }

        int timeStopMinutes = ctx.profile().timeStopMinutes();
        if (ctx.ageMinutes() >= timeStopMinutes && ctx.currentProgress() < minProgressPct) {
            String reason = String.format(
                    "TIME_EXIT: ageMin=%d timeStopMin=%d progress=%.1f minProgressPct=%.1f",
                    ctx.ageMinutes(), timeStopMinutes, ctx.currentProgress(), minProgressPct);
            log.info("smart_exit.time_exit strategy={} symbol={} {}", sig.getStrategyName(), sig.getSymbol(), reason);
            return ExitDecision.timeExit(reason);
        }

        return null;
    }

    private void logPressureExit(StrategySignalEntity sig, PressureExitTrigger trigger, String reason) {
        log.warn("smart_exit.pressure strategy={} symbol={} trigger={} reason={}",
                sig.getStrategyName(), sig.getSymbol(), trigger.name(), reason);
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

        PressureSnapshot snapshot = pressureTracker.getSnapshot(normalizedSymbol);
        PressureAnalysis analysis = pressureTracker.analyze(normalizedSymbol, pressureLookback);

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
                sig, profile, isBuy, snapshot, analysis, recentBars, lastBar,
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

    private boolean isCounterCandle(MarketdataCandle bar, boolean isBuy) {
        if (bar.getHighPrice() == null || bar.getLowPrice() == null
                || bar.getClosePrice() == null || bar.getOpenPrice() == null) {
            return false;
        }

        double high = bar.getHighPrice().doubleValue();
        double low = bar.getLowPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();
        double open = bar.getOpenPrice().doubleValue();
        double range = high - low;
        if (range <= 0) {
            return false;
        }

        double bodyRatio = Math.abs(close - open) / range;
        if (bodyRatio < counterCandleBodyRatio) {
            return false;
        }

        return isBuy ? close < open : close > open;
    }

    private void broadcastOutcomeChange(StrategySignalEntity sig, ExitDecision decision) {
        try {
            String userId = sig.getUserId() != null ? sig.getUserId().toString() : "system";
            SignalPublishedEvent signalEvt = new SignalPublishedEvent(
                    sig.getId(), sig.getUserId(), sig.getSymbol(), sig.getStrategyName());
            OperationalRealtimeEvent opsEvt = new OperationalRealtimeEvent(
                    "signal_outcome",
                    Map.of(
                            "signalId", sig.getId().toString(),
                            "symbol", sig.getSymbol() != null ? sig.getSymbol() : "",
                            "strategyKey", sig.getStrategyName() != null ? sig.getStrategyName() : "",
                            "outcomeStatus", sig.getOutcomeStatus(),
                            "exitCategory", decision.category().name(),
                            "realizedPnl", sig.getRealizedPnl() != null ? sig.getRealizedPnl().toPlainString() : "0",
                            "exitReason", sig.getExpiryReason() != null ? sig.getExpiryReason() : "",
                            "userId", userId
                    )
            );
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishEvent(signalEvt);
                        eventPublisher.publishEvent(opsEvt);
                    }
                });
            } else {
                eventPublisher.publishEvent(signalEvt);
                eventPublisher.publishEvent(opsEvt);
            }
        } catch (Exception ex) {
            log.debug("smart_exit.broadcast_error signalId={}", sig.getId(), ex);
        }
    }

    private record ExitContext(
            StrategySignalEntity signal,
            StrategyLifecycleProfile profile,
            boolean isBuy,
            PressureSnapshot snapshot,
            PressureAnalysis analysis,
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
