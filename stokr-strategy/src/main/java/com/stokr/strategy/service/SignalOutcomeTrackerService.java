package com.stokr.strategy.service;

import com.stokr.common.events.SignalOutcomeEvents;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.lifecycle.ExitCategory;
import com.stokr.strategy.lifecycle.PressureExitTrigger;
import com.stokr.strategy.lifecycle.StrategyExitTelemetryService;
import com.stokr.oms.domain.OmsOrder;
import com.stokr.oms.domain.OrderState;
import com.stokr.oms.repository.OmsOrderRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks signal outcomes by scanning candle data after signal generation time.
 * Updates outcomeStatus (TARGET_HIT / STOPLOSS_HIT / RUNNING / EXPIRED) and realized PnL.
 * Runs every 5 minutes. Processes last 8 hours of untracked signals in batches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalOutcomeTrackerService {

    private static final String STATUS_TARGET_HIT  = "TARGET_HIT";
    private static final String STATUS_SL_HIT      = "STOPLOSS_HIT";
    private static final String STATUS_RUNNING     = "RUNNING";
    private static final String STATUS_TIME_EXIT   = "TIME_EXIT";
    private static final String STATUS_BREAKEVEN   = "BREAKEVEN_EXIT";

    private static final int EXPIRY_HOURS     = 72;
    private static final int BATCH_SIZE       = 200;
    private static final int FAST_BATCH_SIZE  = 50;
    private static final int OPEN_POSITION_LOOKBACK_DAYS = 14;

    private static final Set<OrderState> OPEN_ENTRY_STATES = Set.of(
            OrderState.FILLED,
            OrderState.PARTIALLY_FILLED,
            OrderState.ACCEPTED
    );

    private static final Set<OrderState> OPEN_EXIT_STATES = Set.of(
            OrderState.FILLED,
            OrderState.PARTIALLY_FILLED,
            OrderState.ACCEPTED,
            OrderState.SUBMITTED,
            OrderState.PENDING_SUBMISSION
    );

    private final StrategySignalRepository signalRepository;
    private final OmsOrderRepository omsOrderRepository;
    private final MarketDataQueryService marketDataQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final SignalPriceEnrichmentService signalPriceEnrichmentService;
    private final StrategyExitTelemetryService exitTelemetryService;

    @Value("${stokr.strategy.exit.breakeven-mfe-ratio:0.5}")
    private double breakevenMfeRatio;

    @Value("${stokr.strategy.exit.breakeven-enabled:true}")
    private boolean breakevenExitEnabled;

    @Scheduled(fixedDelayString = "${stokr.signal.outcome-track-ms:300000}")
    @Transactional
    public void trackOutcomes() {
        trackOutcomes(BATCH_SIZE);
    }

    /**
     * Fast-path tracker: re-evaluates signals already marked RUNNING every 30 seconds
     * so PnL and outcome status reflect fresh candle data without waiting 5 minutes.
     */
    @Scheduled(fixedDelayString = "${stokr.signal.outcome-fast-track-ms:30000}")
    @Transactional
    public void trackRunningSignals() {
        Instant since = Instant.now().minus(EXPIRY_HOURS, ChronoUnit.HOURS);
        Map<UUID, StrategySignalEntity> batch = new LinkedHashMap<>();
        for (StrategySignalEntity sig : signalRepository.findRunningSignalsSince(
                since, PageRequest.of(0, FAST_BATCH_SIZE))) {
            batch.put(sig.getId(), sig);
        }
        for (StrategySignalEntity sig : findRunningWithOpenBrokerEntries()) {
            batch.putIfAbsent(sig.getId(), sig);
        }
        List<StrategySignalEntity> running = new ArrayList<>(batch.values());
        if (running.isEmpty()) return;
        Instant now = Instant.now();
        int updated = 0;
        for (StrategySignalEntity sig : running) {
            try {
                signalPriceEnrichmentService.enrichIfMissing(sig,
                        sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt());
                String prevStatus = sig.getOutcomeStatus();
                BigDecimal prevUnrealized = sig.getUnrealizedPnl();
                if (evaluate(sig, now)) {
                    updated++;
                    boolean statusChanged = prevStatus != null && !prevStatus.equals(sig.getOutcomeStatus());
                    boolean pnlChanged = (prevUnrealized == null && sig.getUnrealizedPnl() != null)
                            || (prevUnrealized != null && sig.getUnrealizedPnl() != null
                            && prevUnrealized.compareTo(sig.getUnrealizedPnl()) != 0)
                            || (sig.getRealizedPnl() != null);
                    if (statusChanged || pnlChanged) {
                        broadcastOutcomeChange(sig);
                    }
                }
            } catch (Exception ex) {
                log.debug("signal.fast_track.eval_error signalId={} {}", sig.getId(), ex.getMessage());
            }
        }
        if (updated > 0) {
            log.debug("signal.fast_track.updated count={}", updated);
        }
    }

    /**
     * Lightweight evaluation used by protection engines to ensure MFE/MAE and terminal target/SL
     * states are updated before any emergency exit can close the signal.
     */
    public boolean evaluateSingleSignal(StrategySignalEntity sig, Instant now) {
        if (sig == null) {
            return false;
        }
        Instant refNow = now != null ? now : Instant.now();
        signalPriceEnrichmentService.enrichIfMissing(sig,
                sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt());
        return evaluate(sig, refNow);
    }

    @Transactional
    public int trackAllPending() {
        int total = 0;
        int batch;
        do {
            batch = trackAllPendingBatch(2000);
            total += batch;
        } while (batch == 2000);
        log.info("signal.outcome.backfill_done total={}", total);
        return total;
    }

    private int trackAllPendingBatch(int batchSize) {
        Instant now = Instant.now();
        List<StrategySignalEntity> pending = signalRepository.findAllPendingOutcomeTracking(
                PageRequest.of(0, batchSize));

        if (pending.isEmpty()) return 0;

        int updated = 0;
        for (StrategySignalEntity sig : pending) {
            try {
                signalPriceEnrichmentService.enrichIfMissing(sig,
                        sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt());
                String prevStatus = sig.getOutcomeStatus();
                if (evaluateHistorical(sig, now)) {
                    updated++;
                    if (isTerminalOutcome(sig.getOutcomeStatus())
                            && !java.util.Objects.equals(prevStatus, sig.getOutcomeStatus())) {
                        broadcastOutcomeChange(sig);
                    }
                }
            } catch (Exception ex) {
                log.debug("signal.outcome.eval_error signalId={} {}", sig.getId(), ex.getMessage());
            }
        }
        if (updated > 0) {
            log.info("signal.outcome.tracked updated={} batch={}", updated, pending.size());
        }
        return pending.size();
    }

    private int trackOutcomes(int batchSize) {
        Instant now    = Instant.now();
        Instant since  = now.minus(EXPIRY_HOURS, ChronoUnit.HOURS);
        Instant before = now.minus(2, ChronoUnit.MINUTES);

        List<StrategySignalEntity> pending = signalRepository.findPendingOutcomeTracking(
                since, before, PageRequest.of(0, batchSize));

        if (pending.isEmpty()) return 0;

        int updated = 0;
        for (StrategySignalEntity sig : pending) {
            try {
                signalPriceEnrichmentService.enrichIfMissing(sig,
                        sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt());
                if (evaluate(sig, now)) {
                    updated++;
                    if (!STATUS_RUNNING.equals(sig.getOutcomeStatus())) {
                        broadcastOutcomeChange(sig);
                    }
                }
            } catch (Exception ex) {
                log.debug("signal.outcome.eval_error signalId={} {}", sig.getId(), ex.getMessage());
            }
        }
        if (updated > 0) {
            log.info("signal.outcome.tracked updated={} batch={}", updated, pending.size());
        }
        return pending.size();
    }

    private List<StrategySignalEntity> findRunningWithOpenBrokerEntries() {
        Instant since = Instant.now().minus(OPEN_POSITION_LOOKBACK_DAYS, ChronoUnit.DAYS);
        List<OmsOrder> entries = omsOrderRepository.findRecentFilledEntriesWithSignal(
                since, OPEN_ENTRY_STATES, PageRequest.of(0, FAST_BATCH_SIZE));
        Set<UUID> openSignalIds = new HashSet<>();
        for (OmsOrder entry : entries) {
            if (entry.getSignalId() == null || hasOppositeExitAfter(entry)) {
                continue;
            }
            openSignalIds.add(entry.getSignalId());
        }
        if (openSignalIds.isEmpty()) {
            return List.of();
        }
        return signalRepository.findAllById(openSignalIds).stream()
                .filter(s -> !s.isDeleted() && !Boolean.TRUE.equals(s.getTestTrade()))
                .filter(s -> "RUNNING".equals(s.getOutcomeStatus()) || "PENDING".equals(s.getOutcomeStatus())
                        || s.getOutcomeStatus() == null)
                .toList();
    }

    private boolean hasOppositeExitAfter(OmsOrder entry) {
        if (entry.getUserId() == null || entry.getSymbol() == null || entry.getSide() == null
                || entry.getCreatedAt() == null) {
            return false;
        }
        String exitSide = "BUY".equalsIgnoreCase(entry.getSide()) ? "SELL" : "BUY";
        if (omsOrderRepository.existsOppositeSideAfter(
                entry.getUserId(), entry.getSymbol(), exitSide, entry.getCreatedAt(), OPEN_EXIT_STATES)) {
            return true;
        }
        if (entry.getSignalId() == null) {
            return false;
        }
        return omsOrderRepository.findAllBySignalIdAndDeletedFalseOrderByCreatedAtDesc(entry.getSignalId()).stream()
                .anyMatch(o -> exitSide.equalsIgnoreCase(o.getSide())
                        && OPEN_EXIT_STATES.contains(o.getState())
                        && o.getCreatedAt() != null
                        && o.getCreatedAt().isAfter(entry.getCreatedAt()));
    }

    private boolean evaluateHistorical(StrategySignalEntity sig, Instant now) {
        if (isTerminalOutcome(sig.getOutcomeStatus())) {
            return false;
        }
        BigDecimal entry  = sig.getEntryReferencePrice();
        BigDecimal target = sig.getTargetPrice();
        BigDecimal sl     = sig.getStopPrice();
        SignalType type   = sig.getSignalType();

        if (entry == null || entry.signum() <= 0) {
            expire(sig, now);
            return true;
        }

        Instant signalTime = sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt();
        Instant scanEnd = signalTime.plus(EXPIRY_HOURS, ChronoUnit.HOURS);
        if (scanEnd.isAfter(now)) {
            scanEnd = now;
        }
        boolean isBuy = type == SignalType.BUY;
        BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;

        List<MarketdataCandle> bars = marketDataQueryService.rangeAsc(sig.getSymbol(), "1m", signalTime, scanEnd);
        if (bars.isEmpty()) {
            bars = marketDataQueryService.rangeAsc(sig.getSymbol(), "5m", signalTime, scanEnd);
        }
        List<MarketdataCandle> postSignal = bars.stream()
                .filter(c -> !c.getOpenTime().isBefore(signalTime))
                .toList();
        refreshMarkToMarket(sig, bars, postSignal, entry, isBuy, qty);

        if (target == null || sl == null) {
            SignalLifecycleService.updateOutcome(sig,STATUS_RUNNING);
            sig.setOutcomeTime(now);
            return true;
        }

        if (postSignal.isEmpty()) {
            markRunningNoBars(sig, now);
            return true;
        }

        boolean hitTgt = false;
        boolean hitSl  = false;

        for (MarketdataCandle c : postSignal) {
            if (c.getHighPrice() == null || c.getLowPrice() == null) continue;
            if (isBuy) {
                if (c.getHighPrice().compareTo(target) >= 0) { hitTgt = true; break; }
                if (c.getLowPrice().compareTo(sl) <= 0)      { hitSl  = true; break; }
            } else {
                if (c.getLowPrice().compareTo(target) <= 0)  { hitTgt = true; break; }
                if (c.getHighPrice().compareTo(sl) >= 0)     { hitSl  = true; break; }
            }
        }

        if (hitTgt) {
            applyHitOutcome(sig, STATUS_TARGET_HIT, ExitCategory.TARGET, true, false, entry, target, qty, isBuy, now, null);
        } else if (hitSl) {
            applyHitOutcome(sig, STATUS_SL_HIT, ExitCategory.HARD_STOP, false, true, entry, sl, qty, isBuy, now, null);
        } else {
            closeWithCategory(sig, ExitCategory.TIME_EXIT,
                    "TIME_EXIT: historical_scan_no_target_or_sl_within_window", now, null, false);
        }

        return true;
    }

    private boolean evaluate(StrategySignalEntity sig, Instant now) {
        if (isTerminalOutcome(sig.getOutcomeStatus())) {
            return false;
        }
        BigDecimal entry  = sig.getEntryReferencePrice();
        BigDecimal target = sig.getTargetPrice();
        BigDecimal sl     = sig.getStopPrice();
        SignalType type   = sig.getSignalType();

        if (entry == null || entry.signum() <= 0) {
            expire(sig, now);
            return true;
        }

        // Expired ??? signal older than EXPIRY_HOURS with no outcome
        if (sig.getCreatedAt().isBefore(now.minus(EXPIRY_HOURS, ChronoUnit.HOURS))) {
            expire(sig, now);
            return true;
        }

        Instant signalTime = sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt();
        boolean isBuy = type == SignalType.BUY;
        BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;

        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(sig.getSymbol(), "1m", 480, now);
        if (bars.isEmpty()) {
            bars = marketDataQueryService.lastBarsAscEndingAt(sig.getSymbol(), "5m", 200, now);
        }
        List<MarketdataCandle> postSignal = bars.stream()
                .filter(c -> !c.getOpenTime().isBefore(signalTime))
                .toList();

        refreshMarkToMarket(sig, bars, postSignal, entry, isBuy, qty);

        if (target == null || sl == null) {
            SignalLifecycleService.updateOutcome(sig,STATUS_RUNNING);
            sig.setOutcomeTime(now);
            return true;
        }

        if (postSignal.isEmpty()) {
            markRunningNoBars(sig, now);
            return true;
        }

        boolean hitTgt = false;
        boolean hitSl = false;
        for (MarketdataCandle c : postSignal) {
            if (c.getHighPrice() == null || c.getLowPrice() == null) continue;
            if (isBuy) {
                if (c.getHighPrice().compareTo(target) >= 0) { hitTgt = true; break; }
                if (c.getLowPrice().compareTo(sl) <= 0) { hitSl = true; break; }
            } else {
                if (c.getLowPrice().compareTo(target) <= 0) { hitTgt = true; break; }
                if (c.getHighPrice().compareTo(sl) >= 0) { hitSl = true; break; }
            }
        }

        if (hitTgt) {
            applyHitOutcome(sig, STATUS_TARGET_HIT, ExitCategory.TARGET, true, false, entry, target, qty, isBuy, now, null);
        } else if (hitSl) {
            applyHitOutcome(sig, STATUS_SL_HIT, ExitCategory.HARD_STOP, false, true, entry, sl, qty, isBuy, now, null);
        } else if (tryBreakevenExit(sig, postSignal, entry, target, isBuy, qty, now)) {
            // closed at entry after partial favorable move
        } else {
            SignalLifecycleService.updateOutcome(sig,STATUS_RUNNING);
            sig.setOutcomeTime(now);
        }

        return true;
    }

    private boolean tryBreakevenExit(
            StrategySignalEntity sig,
            List<MarketdataCandle> postSignal,
            BigDecimal entry,
            BigDecimal target,
            boolean isBuy,
            BigDecimal qty,
            Instant now) {
        if (!breakevenExitEnabled || target == null || postSignal.isEmpty()) {
            return false;
        }
        BigDecimal mfe = sig.getMaxFavorableExcursion();
        if (mfe == null || mfe.signum() <= 0) {
            return false;
        }
        BigDecimal targetMove = isBuy
                ? target.subtract(entry).abs()
                : entry.subtract(target).abs();
        if (targetMove.signum() <= 0) {
            return false;
        }
        BigDecimal mfePerUnit = mfe.divide(qty, 6, RoundingMode.HALF_UP);
        if (mfePerUnit.compareTo(targetMove.multiply(BigDecimal.valueOf(breakevenMfeRatio))) < 0) {
            return false;
        }
        MarketdataCandle last = postSignal.get(postSignal.size() - 1);
        if (last.getClosePrice() == null) {
            return false;
        }
        boolean crossedEntry = isBuy
                ? last.getClosePrice().compareTo(entry) <= 0
                : last.getClosePrice().compareTo(entry) >= 0;
        if (!crossedEntry) {
            return false;
        }
        applyHitOutcome(sig, STATUS_BREAKEVEN, ExitCategory.PRESSURE_EXIT, false, false, entry, entry, qty, isBuy, now,
                PressureExitTrigger.TRAILING_BREAKEVEN);
        return true;
    }

    private void markRunningNoBars(StrategySignalEntity sig, Instant now) {
        SignalLifecycleService.updateOutcome(sig,STATUS_RUNNING);
        sig.setOutcomeTime(now);
        ensureUnrealizedPresent(sig);
    }

    private void refreshMarkToMarket(
            StrategySignalEntity sig,
            List<MarketdataCandle> allBars,
            List<MarketdataCandle> postSignal,
            BigDecimal entry,
            boolean isBuy,
            BigDecimal qty) {
        List<MarketdataCandle> mtmBars = postSignal.isEmpty() ? allBars : postSignal;
        if (!mtmBars.isEmpty()) {
            applyExcursionAndMarkToMarket(sig, mtmBars, entry, isBuy, qty);
        } else {
            ensureUnrealizedPresent(sig);
        }
    }

    private void ensureUnrealizedPresent(StrategySignalEntity sig) {
        if (sig.getUnrealizedPnl() == null) {
            sig.setUnrealizedPnl(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void applyHitOutcome(
            StrategySignalEntity sig,
            String status,
            ExitCategory category,
            boolean hitTarget,
            boolean hitSl,
            BigDecimal entry,
            BigDecimal exitLevel,
            BigDecimal qty,
            boolean isBuy,
            Instant now,
            PressureExitTrigger trigger) {
        SignalLifecycleService.updateOutcome(sig,status);
        sig.setHitTarget(hitTarget);
        sig.setHitStoploss(hitSl);
        sig.setOutcomeTime(now);
        sig.setExitPrice(exitLevel);
        if (sig.getEntryPrice() == null) {
            sig.setEntryPrice(entry);
        }
        BigDecimal pnl = isBuy
                ? exitLevel.subtract(entry).multiply(qty)
                : entry.subtract(exitLevel).multiply(qty);
        sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        sig.setUnrealizedPnl(null);
        sig.setExpiryReason(category.name() + ": " + status);

        exitTelemetryService.recordExit(
                sig, category, sig.getExpiryReason(), now, null, trigger, false);
    }

    private void applyExcursionAndMarkToMarket(
            StrategySignalEntity sig,
            List<MarketdataCandle> postSignal,
            BigDecimal entry,
            boolean isBuy,
            BigDecimal qty) {
        double mfe = 0;
        double mae = 0;
        for (MarketdataCandle c : postSignal) {
            if (c.getHighPrice() == null || c.getLowPrice() == null) {
                continue;
            }
            double fav;
            double adv;
            if (isBuy) {
                fav = c.getHighPrice().subtract(entry).doubleValue();
                adv = entry.subtract(c.getLowPrice()).doubleValue();
            } else {
                fav = entry.subtract(c.getLowPrice()).doubleValue();
                adv = c.getHighPrice().subtract(entry).doubleValue();
            }
            mfe = Math.max(mfe, Math.max(0, fav));
            mae = Math.max(mae, Math.max(0, adv));
        }
        sig.setMaxFavorableExcursion(BigDecimal.valueOf(mfe * qty.doubleValue()).setScale(2, RoundingMode.HALF_UP));
        sig.setMaxAdverseExcursion(BigDecimal.valueOf(mae * qty.doubleValue()).setScale(2, RoundingMode.HALF_UP));

        MarketdataCandle last = postSignal.get(postSignal.size() - 1);
        if (last.getClosePrice() != null) {
            BigDecimal uPnl = isBuy
                    ? last.getClosePrice().subtract(entry).multiply(qty)
                    : entry.subtract(last.getClosePrice()).multiply(qty);
            sig.setUnrealizedPnl(uPnl.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void expire(StrategySignalEntity sig, Instant now) {
        if (sig.getEntryReferencePrice() != null && sig.getEntryReferencePrice().signum() > 0) {
            BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;
            boolean isBuy = sig.getSignalType() == SignalType.BUY;
            List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(sig.getSymbol(), "1m", 5, now);
            if (!bars.isEmpty()) {
                refreshMarkToMarket(sig, bars, bars, sig.getEntryReferencePrice(), isBuy, qty);
            }
        }
        closeWithCategory(sig, ExitCategory.TIME_EXIT,
                "TIME_EXIT: signal_expired_after_" + EXPIRY_HOURS + "h", now, null, false);
    }

    private void closeWithCategory(
            StrategySignalEntity sig,
            ExitCategory category,
            String reason,
            Instant now,
            PressureExitTrigger trigger,
            boolean minHoldBypassed) {
        ensureUnrealizedPresent(sig);
        if (sig.getRealizedPnl() == null && sig.getUnrealizedPnl() != null) {
            sig.setRealizedPnl(sig.getUnrealizedPnl());
        }
        sig.setUnrealizedPnl(null);
        SignalLifecycleService.updateOutcome(sig,category.outcomeStatus());
        sig.setOutcomeTime(now);
        sig.setExpiryReason(category.name() + ": " + reason);
        if (sig.getSymbol() != null) {
            exitTelemetryService.recordExit(sig, category, reason, now, null, trigger, minHoldBypassed);
        }
    }

    private static boolean isTerminalOutcome(String status) {
        return ExitCategory.isTerminalOutcome(status);
    }

    private void broadcastOutcomeChange(StrategySignalEntity sig) {
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
                    sig.getRealizedPnl() != null ? sig.getRealizedPnl().toPlainString() : "0"
            ));
        } catch (Exception ex) {
            log.debug("signal.outcome.broadcast_error signalId={}", sig.getId(), ex);
        }
    }
}
