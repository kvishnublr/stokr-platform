package com.stokr.strategy.service;

import com.stokr.common.events.OperationalRealtimeEvent;
import com.stokr.common.events.SignalPublishedEvent;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final String STATUS_EXPIRED     = "EXPIRED";

    private static final int EXPIRY_HOURS     = 8;
    private static final int BATCH_SIZE       = 200;
    private static final int FAST_BATCH_SIZE  = 50;

    private final StrategySignalRepository signalRepository;
    private final MarketDataQueryService marketDataQueryService;
    private final ApplicationEventPublisher eventPublisher;

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
        List<StrategySignalEntity> running = signalRepository.findRunningSignalsSince(
                since, PageRequest.of(0, FAST_BATCH_SIZE));
        if (running.isEmpty()) return;
        Instant now = Instant.now();
        int updated = 0;
        for (StrategySignalEntity sig : running) {
            try {
                String prevStatus = sig.getOutcomeStatus();
                if (evaluate(sig, now)) {
                    updated++;
                    if (!sig.getOutcomeStatus().equals(prevStatus)) {
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
                if (evaluateHistorical(sig, now)) updated++;
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

    private boolean evaluateHistorical(StrategySignalEntity sig, Instant now) {
        BigDecimal entry  = sig.getEntryReferencePrice();
        BigDecimal target = sig.getTargetPrice();
        BigDecimal sl     = sig.getStopPrice();
        SignalType type   = sig.getSignalType();

        if (entry == null || entry.signum() <= 0) {
            expire(sig, now);
            return true;
        }

        if (target == null || sl == null) {
            sig.setOutcomeStatus(STATUS_RUNNING);
            sig.setOutcomeTime(now);
            return true;
        }

        // For historical/replayed signals, use candleTimestamp as the signal time
        Instant signalTime = sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt();
        // Fetch 1m candles in the 8-hour window after the signal candle
        Instant scanEnd = signalTime.plus(EXPIRY_HOURS, ChronoUnit.HOURS);
        if (scanEnd.isAfter(now)) scanEnd = now;

        List<MarketdataCandle> bars = marketDataQueryService.rangeAsc(
                sig.getSymbol(), "1m", signalTime, scanEnd);

        List<MarketdataCandle> postSignal = bars.stream()
                .filter(c -> c.getOpenTime().isAfter(signalTime))
                .toList();

        if (postSignal.isEmpty()) {
            sig.setOutcomeStatus(STATUS_RUNNING);
            sig.setOutcomeTime(now);
            return true;
        }

        boolean isBuy  = type == SignalType.BUY;
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

        BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;

        if (hitTgt) {
            sig.setOutcomeStatus(STATUS_TARGET_HIT);
            sig.setHitTarget(true);
            sig.setOutcomeTime(now);
            BigDecimal pnl = isBuy
                    ? target.subtract(entry).multiply(qty)
                    : entry.subtract(target).multiply(qty);
            sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        } else if (hitSl) {
            sig.setOutcomeStatus(STATUS_SL_HIT);
            sig.setHitStoploss(true);
            sig.setOutcomeTime(now);
            BigDecimal pnl = isBuy
                    ? sl.subtract(entry).multiply(qty)
                    : entry.subtract(sl).multiply(qty);
            sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        } else {
            // All bars scanned with no hit — mark EXPIRED for historical signals
            sig.setOutcomeStatus(STATUS_EXPIRED);
            sig.setOutcomeTime(now);
        }

        return true;
    }

    private boolean evaluate(StrategySignalEntity sig, Instant now) {
        BigDecimal entry  = sig.getEntryReferencePrice();
        BigDecimal target = sig.getTargetPrice();
        BigDecimal sl     = sig.getStopPrice();
        SignalType type   = sig.getSignalType();

        if (entry == null || entry.signum() <= 0) {
            expire(sig, now);
            return true;
        }

        // Expired — signal older than EXPIRY_HOURS with no outcome
        if (sig.getCreatedAt().isBefore(now.minus(EXPIRY_HOURS, ChronoUnit.HOURS))) {
            expire(sig, now);
            return true;
        }

        // No target/SL → mark as RUNNING so it won't be re-processed endlessly
        if (target == null || sl == null) {
            sig.setOutcomeStatus(STATUS_RUNNING);
            sig.setOutcomeTime(now);
            return true;
        }

        // Fetch 1m candles after signal creation time
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(
                sig.getSymbol(), "1m", 480, now);

        // Filter to only bars after signal was generated
        Instant signalTime = sig.getCandleTimestamp() != null ? sig.getCandleTimestamp() : sig.getCreatedAt();
        List<MarketdataCandle> postSignal = bars.stream()
                .filter(c -> c.getOpenTime().isAfter(signalTime))
                .toList();

        if (postSignal.isEmpty()) {
            sig.setOutcomeStatus(STATUS_RUNNING);
            sig.setOutcomeTime(now);
            return true;
        }

        boolean isBuy  = type == SignalType.BUY;
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

        BigDecimal qty = sig.getSuggestedQty() != null ? sig.getSuggestedQty() : BigDecimal.ONE;

        if (hitTgt) {
            sig.setOutcomeStatus(STATUS_TARGET_HIT);
            sig.setHitTarget(true);
            sig.setOutcomeTime(now);
            BigDecimal pnl = isBuy
                    ? target.subtract(entry).multiply(qty)
                    : entry.subtract(target).multiply(qty);
            sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        } else if (hitSl) {
            sig.setOutcomeStatus(STATUS_SL_HIT);
            sig.setHitStoploss(true);
            sig.setOutcomeTime(now);
            BigDecimal pnl = isBuy
                    ? sl.subtract(entry).multiply(qty)
                    : entry.subtract(sl).multiply(qty);
            sig.setRealizedPnl(pnl.setScale(2, RoundingMode.HALF_UP));
        } else {
            sig.setOutcomeStatus(STATUS_RUNNING);
            sig.setOutcomeTime(now);
        }

        return true;
    }

    private void expire(StrategySignalEntity sig, Instant now) {
        sig.setOutcomeStatus(STATUS_EXPIRED);
        sig.setOutcomeTime(now);
    }

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
            log.debug("signal.outcome.broadcast_error signalId={}", sig.getId(), ex);
        }
    }
}
