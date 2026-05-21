package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.repository.StrategySignalRepository;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    private static final int EXPIRY_HOURS = 8;
    private static final int BATCH_SIZE   = 200;

    private final StrategySignalRepository signalRepository;
    private final MarketDataQueryService marketDataQueryService;

    @Scheduled(fixedDelayString = "${stokr.signal.outcome-track-ms:300000}")
    @Transactional
    public void trackOutcomes() {
        trackOutcomes(BATCH_SIZE);
    }

    @Transactional
    public int trackAllPending() {
        int total = 0;
        int batch;
        do {
            batch = trackOutcomes(2000);
            total += batch;
        } while (batch == 2000);
        log.info("signal.outcome.backfill_done total={}", total);
        return total;
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
                if (evaluate(sig, now)) updated++;
            } catch (Exception ex) {
                log.debug("signal.outcome.eval_error signalId={} {}", sig.getId(), ex.getMessage());
            }
        }
        if (updated > 0) {
            log.info("signal.outcome.tracked updated={} batch={}", updated, pending.size());
        }
        return pending.size();
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
}
