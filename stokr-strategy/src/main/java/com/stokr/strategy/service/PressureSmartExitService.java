package com.stokr.strategy.service;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.marketdata.service.OrderBookPressureTracker;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureAnalysis;
import com.stokr.marketdata.service.OrderBookPressureTracker.PressureSnapshot;
import com.stokr.strategy.domain.StrategySignalEntity;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * PRESSURE SMART EXIT SERVICE — V3.5 Smart Exit Engine
 *
 * Monitors RUNNING signals for all pressure-based strategies and exits EARLY when
 * order book pressure reverses, before the stop loss gets hit.
 *
 * WHY THIS EXISTS:
 *   Data from May 26 shows 25% of SL hits had MFE > 0.5% (price went
 *   favorable first, then reversed to SL). Smart exit catches these
 *   reversals and exits at breakeven or small profit instead of full SL loss.
 *
 * EXIT RULES (checked every 15 seconds):
 *   ① PRESSURE REVERSAL — Buy pressure flipped to sell (or vice versa)
 *   ② PRESSURE EXHAUSTION — Pressure consistency dropped below 35%
 *   ③ TRAILING BREAKEVEN — MFE > 40% of target distance → trail SL to entry
 *   ④ COUNTER CANDLE — Strong counter-direction bar appeared
 *   ⑤ TIME DECAY — Signal > 20 min old with < 20% progress toward target
 *
 * OUTCOME: Marks signal as "PRESSURE_EXIT" with exit price = current close.
 *   This converts ~50% of would-be SL hits into breakevens/small wins.
 *
 * EXPECTED IMPACT: Pushes win rate from 65-75% → 75-85%
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PressureSmartExitService {

    /** All pressure-based strategies eligible for smart exit */
    private static final java.util.Set<String> ELIGIBLE_STRATEGIES = java.util.Set.of(
            "NSE_SPIKE_DETECTION", "EARLY_BREAKOUT", "VWAP_BOUNCE", "GAP_FILL", "SECTOR_LAGGARD"
    );
    private static final String STATUS_PRESSURE_EXIT = "PRESSURE_EXIT";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final int MAX_SIGNALS_PER_SCAN = 100;
    private static final int LOOKBACK_HOURS = 8;

    private final StrategySignalRepository signalRepository;
    private final OrderBookPressureTracker pressureTracker;
    private final MarketDataQueryService marketDataQueryService;
    private final ApplicationEventPublisher eventPublisher;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURABLE THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Pressure ratio at which a buy signal is considered reversed (sell pressure dominant) */
    @Value("${stokr.strategy.smart-exit.pressure-reversal-buy:0.43}")
    private double pressureReversalBuyThreshold;

    /** Pressure ratio at which a sell signal is considered reversed (buy pressure dominant) */
    @Value("${stokr.strategy.smart-exit.pressure-reversal-sell:0.57}")
    private double pressureReversalSellThreshold;

    /** Minimum consistency below which pressure is considered exhausted */
    @Value("${stokr.strategy.smart-exit.exhaustion-consistency:0.35}")
    private double exhaustionConsistency;

    /** Lookback ticks for pressure analysis during exit monitoring */
    @Value("${stokr.strategy.smart-exit.pressure-lookback:60}")
    private int pressureLookback;

    /** MFE ratio of target distance to activate trailing breakeven */
    @Value("${stokr.strategy.smart-exit.trailing-mfe-ratio:0.40}")
    private double trailingMfeRatio;

    /** Minutes after which time decay exit kicks in */
    @Value("${stokr.strategy.smart-exit.time-decay-minutes:20}")
    private int timeDecayMinutes;

    /** Minimum progress toward target (%) to stay alive after time decay */
    @Value("${stokr.strategy.smart-exit.min-progress-pct:20}")
    private double minProgressPct;

    /** Counter candle minimum body ratio (body/range) to trigger exit */
    @Value("${stokr.strategy.smart-exit.counter-candle-body-ratio:0.55}")
    private double counterCandleBodyRatio;

    /** Enable/disable the smart exit service */
    @Value("${stokr.strategy.smart-exit.enabled:true}")
    private boolean enabled;

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCHEDULED SCAN — every 15 seconds
    // ═══════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "${stokr.strategy.smart-exit.interval-ms:15000}")
    @Transactional
    public void monitorAndExit() {
        if (!enabled) return;

        Instant since = Instant.now().minus(LOOKBACK_HOURS, ChronoUnit.HOURS);
        List<StrategySignalEntity> running = signalRepository.findRunningSignalsSince(
                since, PageRequest.of(0, MAX_SIGNALS_PER_SCAN));

        if (running.isEmpty()) return;

        // Filter to pressure-based strategies with proper entry/SL/target
        List<StrategySignalEntity> spikeSignals = running.stream()
                .filter(s -> s.getStrategyName() != null && ELIGIBLE_STRATEGIES.contains(s.getStrategyName().toUpperCase()))
                .filter(s -> s.getEntryReferencePrice() != null && s.getEntryReferencePrice().signum() > 0)
                .filter(s -> s.getStopPrice() != null && s.getTargetPrice() != null)
                .toList();

        if (spikeSignals.isEmpty()) return;

        Instant now = Instant.now();
        int exited = 0;

        for (StrategySignalEntity sig : spikeSignals) {
            try {
                String exitReason = evaluateSmartExit(sig, now);
                if (exitReason != null) {
                    applyPressureExit(sig, exitReason, now);
                    exited++;
                    broadcastOutcomeChange(sig);
                }
            } catch (Exception ex) {
                log.debug("smart_exit.eval_error signalId={} {}", sig.getId(), ex.getMessage());
            }
        }

        if (exited > 0) {
            log.info("smart_exit.scan scanned={} exited={}", spikeSignals.size(), exited);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SMART EXIT EVALUATION — returns exit reason or null (stay in trade)
    // ═══════════════════════════════════════════════════════════════════════════

    private String evaluateSmartExit(StrategySignalEntity sig, Instant now) {
        String symbol = sig.getSymbol();
        boolean isBuy = sig.getSignalType() == SignalType.BUY;
        BigDecimal entry = sig.getEntryReferencePrice();
        BigDecimal target = sig.getTargetPrice();
        BigDecimal sl = sig.getStopPrice();

        // Get current pressure state
        PressureSnapshot snapshot = pressureTracker.getSnapshot(symbol);
        PressureAnalysis analysis = pressureTracker.analyze(symbol, pressureLookback);

        // Get latest candle for price
        List<MarketdataCandle> recentBars = marketDataQueryService.lastBarsAsc(symbol, "1m", 3);
        if (recentBars.isEmpty()) return null;

        MarketdataCandle lastBar = recentBars.get(recentBars.size() - 1);
        if (lastBar.getClosePrice() == null) return null;

        double currentPrice = lastBar.getClosePrice().doubleValue();
        double entryPrice = entry.doubleValue();
        double targetPrice = target.doubleValue();
        double slPrice = sl.doubleValue();

        // Calculate current P&L direction
        double pnlPct;
        if (isBuy) {
            pnlPct = (currentPrice - entryPrice) / entryPrice * 100;
        } else {
            pnlPct = (entryPrice - currentPrice) / entryPrice * 100;
        }

        // Calculate progress toward target (0% = at entry, 100% = at target)
        double targetDistance = Math.abs(targetPrice - entryPrice);
        double currentProgress = targetDistance > 0
                ? Math.abs(currentPrice - entryPrice) / targetDistance * 100
                : 0;
        // Negative progress if price moved AGAINST us
        if (pnlPct < 0) currentProgress = -currentProgress;

        // MFE from entity (set by outcome tracker)
        double mfe = sig.getMaxFavorableExcursion() != null
                ? sig.getMaxFavorableExcursion().doubleValue()
                : 0;
        double mfePctOfTarget = targetDistance > 0 ? mfe / targetDistance * 100 : 0;

        // Signal age in minutes
        Instant signalTime = sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt();
        long ageMinutes = ChronoUnit.MINUTES.between(signalTime, now);

        // ─────────────────────────────────────────────────────────────────────
        // RULE ①: PRESSURE REVERSAL
        // Buy signal but sell pressure now dominant (or vice versa)
        // Only exit if we're not already significantly in profit
        // ─────────────────────────────────────────────────────────────────────
        if (snapshot != null && analysis != null) {
            double ratio = snapshot.imbalanceRatio();

            if (isBuy && ratio < pressureReversalBuyThreshold) {
                // Buy pressure GONE — sell side dominant
                if (currentProgress < 70) {  // Don't exit if almost at target
                    return String.format("PRESSURE_REVERSAL: buy→sell ratio=%.0f%% price_progress=%.0f%%",
                            ratio * 100, currentProgress);
                }
            }
            if (!isBuy && ratio > pressureReversalSellThreshold) {
                // Sell pressure GONE — buy side dominant
                if (currentProgress < 70) {
                    return String.format("PRESSURE_REVERSAL: sell→buy ratio=%.0f%% price_progress=%.0f%%",
                            ratio * 100, currentProgress);
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // RULE ②: PRESSURE EXHAUSTION
        // Pressure consistency dropped to noise level — institutions done
        // Only trigger if position is not in strong profit
        // ─────────────────────────────────────────────────────────────────────
        if (analysis != null) {
            double consistency = analysis.pressureConsistency();
            if (consistency < exhaustionConsistency && currentProgress < 50) {
                return String.format("PRESSURE_EXHAUSTION: consistency=%.0f%% (<%d%%) progress=%.0f%%",
                        consistency * 100, (int)(exhaustionConsistency * 100), currentProgress);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // RULE ③: TRAILING BREAKEVEN
        // If MFE reached 40%+ of target distance but price came back to entry
        // → exit at breakeven (don't let a winning trade become a loser)
        // ─────────────────────────────────────────────────────────────────────
        if (mfePctOfTarget >= trailingMfeRatio * 100) {
            // We HAD a good move — check if it's reversing
            boolean backAtEntry;
            if (isBuy) {
                backAtEntry = currentPrice <= entryPrice * 1.001;  // Within 0.1% of entry
            } else {
                backAtEntry = currentPrice >= entryPrice * 0.999;
            }

            if (backAtEntry) {
                return String.format("TRAILING_BREAKEVEN: mfe_pct=%.0f%% of target, price returned to entry",
                        mfePctOfTarget);
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // RULE ④: COUNTER CANDLE
        // A strong candle appeared in the opposite direction — momentum reversed
        // Only trigger if we've been in the trade > 3 minutes (avoid noise)
        // ─────────────────────────────────────────────────────────────────────
        if (ageMinutes >= 3 && recentBars.size() >= 2) {
            MarketdataCandle prevBar = recentBars.get(recentBars.size() - 2);
            if (isCounterCandle(lastBar, isBuy)) {
                // Also check: is previous bar also counter? (2 counter bars = strong reversal)
                if (isCounterCandle(prevBar, isBuy)) {
                    return String.format("COUNTER_CANDLE: 2 consecutive counter bars, pnl=%.2f%%", pnlPct);
                }
                // Single counter candle: only exit if we're losing or flat
                if (pnlPct <= 0.05) {
                    return String.format("COUNTER_CANDLE: strong reversal bar + flat/losing pnl=%.2f%%", pnlPct);
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // RULE ⑤: TIME DECAY
        // Signal is old with no meaningful progress — opportunity cost
        // After 20 min, if < 20% progress toward target, exit
        // ─────────────────────────────────────────────────────────────────────
        if (ageMinutes >= timeDecayMinutes && currentProgress < minProgressPct) {
            return String.format("TIME_DECAY: age=%dmin progress=%.0f%% (min=%d%%)",
                    ageMinutes, currentProgress, (int) minProgressPct);
        }

        // All rules passed — stay in trade
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER: Check if a candle is a counter-direction candle
    // ═══════════════════════════════════════════════════════════════════════════

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

        if (range <= 0) return false;

        double bodySize = Math.abs(close - open);
        double bodyRatio = bodySize / range;

        if (bodyRatio < counterCandleBodyRatio) return false;  // Not strong enough

        if (isBuy) {
            return close < open;  // Red bar = counter for buy
        } else {
            return close > open;  // Green bar = counter for sell
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // APPLY EXIT: Update signal entity with PRESSURE_EXIT outcome
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyPressureExit(StrategySignalEntity sig, String exitReason, Instant now) {
        String symbol = sig.getSymbol();
        boolean isBuy = sig.getSignalType() == SignalType.BUY;
        BigDecimal entry = sig.getEntryReferencePrice();
        BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;

        // Get current price for exit
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, "1m", 1);
        BigDecimal exitPrice = entry;  // Default to breakeven if no candle
        if (!bars.isEmpty() && bars.get(0).getClosePrice() != null) {
            exitPrice = bars.get(0).getClosePrice();
        }

        // Calculate realized PnL
        BigDecimal pnl;
        if (isBuy) {
            pnl = exitPrice.subtract(entry).multiply(qty);
        } else {
            pnl = entry.subtract(exitPrice).multiply(qty);
        }

        // Update signal
        sig.setOutcomeStatus(STATUS_PRESSURE_EXIT);
        sig.setOutcomeTime(now);
        sig.setExitPrice(exitPrice);
        sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        sig.setUnrealizedPnl(null);
        sig.setExpiryReason("SMART_EXIT: " + exitReason);

        if (sig.getEntryPrice() == null) {
            sig.setEntryPrice(entry);
        }

        log.info("smart_exit.closed symbol={} type={} entry={} exit={} pnl={} reason={}",
                symbol, sig.getSignalType(), entry, exitPrice, pnl.setScale(2, RoundingMode.HALF_UP), exitReason);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BROADCAST: Notify UI of outcome change
    // ═══════════════════════════════════════════════════════════════════════════

    private void broadcastOutcomeChange(StrategySignalEntity sig) {
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
}
