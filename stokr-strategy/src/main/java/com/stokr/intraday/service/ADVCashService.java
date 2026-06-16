package com.stokr.intraday.service;

import com.stokr.intraday.detector.ADVCashDetector;
import com.stokr.intraday.domain.EquitySignal;
import com.stokr.intraday.repository.EquitySignalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ADVCashService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final EquitySignalRepository equitySignalRepository;
    private final ADVCashDetector advCashDetector;

    // Deduplication window: 30 minutes
    private static final long DEDUP_WINDOW_MS = 30 * 60 * 1000;

    // Sector rotation: max 2 concurrent positions
    private static final int MAX_SECTOR_POSITIONS = 2;

    public ADVCashService(EquitySignalRepository equitySignalRepository,
                        ADVCashDetector advCashDetector) {
        this.equitySignalRepository = equitySignalRepository;
        this.advCashDetector = advCashDetector;
    }

    /**
     * Run full ADV_CASH detection with deduplication and sector rotation
     */
    public List<EquitySignal> runFullDetection() {
        List<EquitySignal> signals = new ArrayList<>();

        try {
            // ADV_CASH Detection
            signals.addAll(advCashDetector.detectSignals());
        } catch (Exception e) {
            log.error("ADV_CASH.detection_failed error={}", e.getMessage());
        }

        // Apply sector rotation rule (max 2 per sector)
        signals = applySectorRotation(signals);

        // Remove duplicates (same symbol within 30 min)
        signals = deduplicateSignals(signals);

        // Persist to database
        for (EquitySignal signal : signals) {
            equitySignalRepository.save(signal);
        }

        log.info("ADV_CASH.detection_complete total_signals={}", signals.size());
        return signals;
    }

    /**
     * Apply sector rotation: max 2 concurrent positions per sector
     */
    private List<EquitySignal> applySectorRotation(List<EquitySignal> signals) {
        Map<String, List<EquitySignal>> bySector = signals.stream()
                .collect(Collectors.groupingBy(EquitySignal::getSector));

        List<EquitySignal> result = new ArrayList<>();

        for (Map.Entry<String, List<EquitySignal>> entry : bySector.entrySet()) {
            String sector = entry.getKey();
            List<EquitySignal> sectorSignals = entry.getValue();

            // Count active positions in this sector
            int activeInSector = equitySignalRepository.countActiveBySector(sector);

            // Keep only first N signals to maintain max 2 per sector
            int canAdd = MAX_SECTOR_POSITIONS - activeInSector;
            if (canAdd > 0) {
                // Sort by quality score (highest first)
                sectorSignals.sort((a, b) -> b.getQualityScore().compareTo(a.getQualityScore()));
                result.addAll(sectorSignals.stream().limit(canAdd).collect(Collectors.toList()));
            }
        }

        return result;
    }

    /**
     * Deduplicate signals: remove same symbol within 30 minutes
     */
    private List<EquitySignal> deduplicateSignals(List<EquitySignal> signals) {
        Map<String, EquitySignal> dedupMap = new LinkedHashMap<>();

        for (EquitySignal newSignal : signals) {
            String key = newSignal.getSymbolName();

            // Check existing signals in database
            EquitySignal existing = equitySignalRepository.findActivePendingSignals()
                    .stream()
                    .filter(s -> s.getSymbolName().equals(newSignal.getSymbolName()))
                    .findFirst()
                    .orElse(null);

            // If existing signal is recent (within 30 min), skip new one
            if (existing != null) {
                long timeDiff = Instant.now().toEpochMilli() - existing.getTimeDetected().toEpochMilli();
                if (timeDiff < DEDUP_WINDOW_MS) {
                    log.debug("ADV_CASH.signal_deduped symbol={}", newSignal.getSymbolName());
                    continue;
                }
            }

            // Keep highest quality signal for this symbol
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
    public List<EquitySignal> getActivePendingSignals() {
        return equitySignalRepository.findActivePendingSignals();
    }

    /**
     * Get high confidence signals (3-TF alignment)
     */
    public List<EquitySignal> getHighConfidenceSignals() {
        return equitySignalRepository.findHighConfidenceSignals();
    }

    /**
     * Get high quality signals
     */
    public List<EquitySignal> getHighQualitySignals(BigDecimal minQuality) {
        return equitySignalRepository.findHighQualitySignals(minQuality);
    }

    /**
     * Get signals by sector
     */
    public List<EquitySignal> getSignalsBySector(String sector) {
        return equitySignalRepository.findBySector(sector);
    }

    /**
     * Mark signal as filled (entry executed)
     */
    public EquitySignal markSignalFilled(Long signalId, BigDecimal actualEntryPrice, Integer quantity) {
        EquitySignal signal = equitySignalRepository.findById(signalId)
                .orElseThrow(() -> new RuntimeException("Signal not found"));

        signal.setActualEntryPrice(actualEntryPrice);
        signal.setTimeFilledAt(Instant.now());
        signal.setQuantity(quantity);
        signal.setExecutionStatus("FILLED");

        // Calculate slippage
        BigDecimal slippage = signal.getDirection().equals("LONG") ?
                actualEntryPrice.subtract(signal.getEntryLevel()) :
                signal.getEntryLevel().subtract(actualEntryPrice);
        signal.setSlippageEntry(slippage);

        equitySignalRepository.save(signal);
        log.info("ADV_CASH.signal_filled signal_id={} symbol={} entry={}",
                signalId, signal.getSymbolName(), actualEntryPrice);

        return signal;
    }

    /**
     * Mark signal as closed (exit executed)
     */
    public EquitySignal markSignalClosed(Long signalId, BigDecimal actualExitPrice, String outcome) {
        EquitySignal signal = equitySignalRepository.findById(signalId)
                .orElseThrow(() -> new RuntimeException("Signal not found"));

        signal.setActualExitPrice(actualExitPrice);
        signal.setTimeClosedAt(Instant.now());
        signal.setOutcomeType(outcome);
        signal.setExecutionStatus("CLOSED");
        signal.setActive(false);

        // Calculate P&L
        BigDecimal pnl;
        if (signal.getDirection().equals("LONG")) {
            pnl = actualExitPrice.subtract(signal.getActualEntryPrice());
        } else {
            pnl = signal.getActualEntryPrice().subtract(actualExitPrice);
        }

        // Multiply by quantity
        int qty = signal.getQuantity() != null ? signal.getQuantity() : 1;
        BigDecimal totalPnL = pnl.multiply(BigDecimal.valueOf(qty));

        signal.setProfitLoss(totalPnL);
        signal.setSlippageExit(actualExitPrice.subtract(signal.getTargetLevel1()).abs());

        equitySignalRepository.save(signal);
        log.info("ADV_CASH.signal_closed signal_id={} symbol={} outcome={} pnl={}",
                signalId, signal.getSymbolName(), outcome, totalPnL);

        return signal;
    }

    /**
     * Get daily statistics
     */
    public ADVCashDailyStats getDailyStats() {
        Instant startOfDay = Instant.now().atZone(IST).truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        List<EquitySignal> todaysSignals = equitySignalRepository.findSignalsCreatedBetween(startOfDay, endOfDay);

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
                .map(EquitySignal::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double winRate = totalTrades > 0 ? (100.0 * wins / totalTrades) : 0.0;

        return new ADVCashDailyStats(totalTrades, wins, losses, winRate, totalPnL);
    }

    // ===== DTOs =====

    public static class ADVCashDailyStats {
        public long totalTrades;
        public long wins;
        public long losses;
        public double winRate;
        public BigDecimal totalPnL;

        public ADVCashDailyStats(long totalTrades, long wins, long losses, double winRate, BigDecimal totalPnL) {
            this.totalTrades = totalTrades;
            this.wins = wins;
            this.losses = losses;
            this.winRate = winRate;
            this.totalPnL = totalPnL;
        }
    }
}
