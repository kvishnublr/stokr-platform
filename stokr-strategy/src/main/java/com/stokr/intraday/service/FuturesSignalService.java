package com.stokr.intraday.service;

import com.stokr.intraday.detector.S3VWAPDetector;
import com.stokr.intraday.detector.S7RangeFadeDetector;
import com.stokr.intraday.domain.FuturesSignal;
import com.stokr.intraday.repository.FuturesSignalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class FuturesSignalService {

    private final FuturesSignalRepository futuresSignalRepository;
    private final S3VWAPDetector s3Detector;
    private final S7RangeFadeDetector s7Detector;

    // Deduplication window: 30 minutes
    private static final long DEDUP_WINDOW_MS = 30 * 60 * 1000;

    public FuturesSignalService(FuturesSignalRepository futuresSignalRepository,
                                S3VWAPDetector s3Detector,
                                S7RangeFadeDetector s7Detector) {
        this.futuresSignalRepository = futuresSignalRepository;
        this.s3Detector = s3Detector;
        this.s7Detector = s7Detector;
    }

    /**
     * Run full detection for both S3 and S7
     */
    public List<FuturesSignal> runFullDetection() {
        List<FuturesSignal> signals = new ArrayList<>();

        // S3 Detection
        try {
            signals.addAll(s3Detector.detectSignals());
        } catch (Exception e) {
            log.error("S3.detection_failed error={}", e.getMessage());
        }

        // S7 Detection
        try {
            signals.addAll(s7Detector.detectSignals());
        } catch (Exception e) {
            log.error("S7.detection_failed error={}", e.getMessage());
        }

        // Remove duplicates (same symbol/direction within 30 min)
        signals = deduplicateSignals(signals);

        // Persist to database
        for (FuturesSignal signal : signals) {
            futuresSignalRepository.save(signal);
        }

        log.info("FUTURES.detection_complete total_signals={}", signals.size());
        return signals;
    }

    /**
     * Deduplicate signals: remove same symbol/direction within 30 minutes
     */
    private List<FuturesSignal> deduplicateSignals(List<FuturesSignal> signals) {
        Map<String, FuturesSignal> dedupMap = new LinkedHashMap<>();

        for (FuturesSignal newSignal : signals) {
            String key = newSignal.getSymbolName() + "_" + newSignal.getDirection() + "_" +
                    newSignal.getStrategyName();

            // Check existing signals in database
            FuturesSignal existing = futuresSignalRepository.findActivePendingSignals()
                    .stream()
                    .filter(s -> s.getSymbolName().equals(newSignal.getSymbolName()) &&
                            s.getDirection().equals(newSignal.getDirection()) &&
                            s.getStrategyName().equals(newSignal.getStrategyName()))
                    .findFirst()
                    .orElse(null);

            // If existing signal is recent (within 30 min), skip new one
            if (existing != null) {
                long timeDiff = Instant.now().toEpochMilli() - existing.getTimeDetected().toEpochMilli();
                if (timeDiff < DEDUP_WINDOW_MS) {
                    log.debug("FUTURES.signal_deduped symbol={} direction={}",
                            newSignal.getSymbolName(), newSignal.getDirection());
                    continue;
                }
            }

            // Keep highest quality signal for this key
            if (!dedupMap.containsKey(key) ||
                    newSignal.getQualityScore().compareTo(dedupMap.get(key).getQualityScore()) > 0) {
                dedupMap.put(key, newSignal);
            }
        }

        return new ArrayList<>(dedupMap.values());
    }

    /**
     * Get active pending signals
     */
    public List<FuturesSignal> getActivePendingSignals() {
        return futuresSignalRepository.findActivePendingSignals();
    }

    /**
     * Get signals by strategy
     */
    public List<FuturesSignal> getSignalsByStrategy(String strategy) {
        return futuresSignalRepository.findByStrategy(strategy);
    }

    /**
     * Get high quality signals
     */
    public List<FuturesSignal> getHighQualitySignals(BigDecimal minQuality) {
        return futuresSignalRepository.findHighQualitySignals(minQuality);
    }

    /**
     * Mark signal as filled (entry executed)
     */
    public FuturesSignal markSignalFilled(Long signalId, BigDecimal actualEntryPrice) {
        FuturesSignal signal = futuresSignalRepository.findById(signalId)
                .orElseThrow(() -> new RuntimeException("Signal not found"));

        signal.setActualEntryPrice(actualEntryPrice);
        signal.setTimeFilledAt(Instant.now());
        signal.setExecutionStatus("FILLED");

        // Calculate slippage
        BigDecimal slippage = signal.getDirection().equals("LONG") ?
                actualEntryPrice.subtract(signal.getEntryLevel()) :
                signal.getEntryLevel().subtract(actualEntryPrice);
        signal.setSlippageEntry(slippage);

        futuresSignalRepository.save(signal);
        log.info("FUTURES.signal_filled signal_id={} strategy={} entry={}",
                signalId, signal.getStrategyName(), actualEntryPrice);

        return signal;
    }

    /**
     * Mark signal as closed (exit executed)
     */
    public FuturesSignal markSignalClosed(Long signalId, BigDecimal actualExitPrice, String outcome) {
        FuturesSignal signal = futuresSignalRepository.findById(signalId)
                .orElseThrow(() -> new RuntimeException("Signal not found"));

        signal.setActualExitPrice(actualExitPrice);
        signal.setTimeClosedAt(Instant.now());
        signal.setOutcomeType(outcome);
        signal.setExecutionStatus("CLOSED");
        signal.setActive(false);

        // Calculate P&L and slippage
        BigDecimal pnl;
        if (signal.getDirection().equals("LONG")) {
            pnl = actualExitPrice.subtract(signal.getActualEntryPrice());
        } else {
            pnl = signal.getActualEntryPrice().subtract(actualExitPrice);
        }

        // Multiply by lot size
        int lotSize = signal.getLotSize() != null ? signal.getLotSize() : 1;
        BigDecimal totalPnL = pnl.multiply(BigDecimal.valueOf(lotSize * 100)); // 100 for rupee conversion

        signal.setProfitLoss(totalPnL);
        signal.setSlippageExit(actualExitPrice.subtract(signal.getTargetLevel1()).abs());

        futuresSignalRepository.save(signal);
        log.info("FUTURES.signal_closed signal_id={} strategy={} outcome={} pnl={}",
                signalId, signal.getStrategyName(), outcome, totalPnL);

        return signal;
    }

    /**
     * Get daily statistics
     */
    public FuturesDailyStats getDailyStats() {
        List<FuturesSignal> todaysSignals = futuresSignalRepository.findTodaysSignals();

        long totalTrades = todaysSignals.stream()
                .filter(s -> s.getOutcomeType() != null)
                .count();

        long wins = todaysSignals.stream()
                .filter(s -> "WIN".equals(s.getOutcomeType()))
                .count();

        long losses = todaysSignals.stream()
                .filter(s -> "LOSS".equals(s.getOutcomeType()))
                .count();

        BigDecimal totalPnL = todaysSignals.stream()
                .filter(s -> s.getProfitLoss() != null)
                .map(FuturesSignal::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double winRate = totalTrades > 0 ? (100.0 * wins / totalTrades) : 0.0;

        return new FuturesDailyStats(totalTrades, wins, losses, winRate, totalPnL);
    }

    // ===== DTOs =====

    public static class FuturesDailyStats {
        public long totalTrades;
        public long wins;
        public long losses;
        public double winRate;
        public BigDecimal totalPnL;

        public FuturesDailyStats(long totalTrades, long wins, long losses, double winRate, BigDecimal totalPnL) {
            this.totalTrades = totalTrades;
            this.wins = wins;
            this.losses = losses;
            this.winRate = winRate;
            this.totalPnL = totalPnL;
        }
    }
}
