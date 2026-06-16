package com.stokr.intraday.service;

import com.stokr.intraday.detector.IndexHuntDetector;
import com.stokr.intraday.detector.MarketDataProvider;
import com.stokr.intraday.domain.IndexSignal;
import com.stokr.intraday.repository.IndexSignalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * INDEX HUNT Service: Orchestrates signal detection, deduplication, daily ranking
 *
 * Responsibilities:
 * 1. Run INDEX HUNT detection for NIFTY + BANKNIFTY
 * 2. Apply deduplication (no same index/direction within 30 min)
 * 3. Rank signals by quality (daily pick: top 1-3 per symbol)
 * 4. Track outcomes (T1 hits, SL hits, expirations)
 * 5. Calculate daily statistics (win rate, P&L, consistency)
 */
@Service
@Slf4j
public class IndexHuntService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final IndexHuntDetector indexHuntDetector;
    private final IndexSignalRepository indexSignalRepository;
    private final MarketDataProvider marketDataProvider;

    // Configuration (from PRECISION_V2)
    private static final int DEDUP_MINUTES = 30;        // No repeat within 30 min
    private static final int DAILY_PICK_MIN = 1;        // At least 1 trade/day
    private static final int DAILY_PICK_MAX = 3;        // Max 3 trades/day
    private static final int DAILY_PICK_GAP_MIN = 36;   // Time-space 36 min apart
    private static final BigDecimal QUALITY_PREMIUM = BigDecimal.valueOf(76);  // Premium tier >= 76

    public IndexHuntService(IndexHuntDetector indexHuntDetector,
                           IndexSignalRepository indexSignalRepository,
                           MarketDataProvider marketDataProvider) {
        this.indexHuntDetector = indexHuntDetector;
        this.indexSignalRepository = indexSignalRepository;
        this.marketDataProvider = marketDataProvider;
    }

    /**
     * Run full INDEX HUNT detection cycle
     * Detects signals for both NIFTY and BANKNIFTY, applies filters
     */
    public List<IndexSignal> runFullDetection() {
        List<IndexSignal> allSignals = new ArrayList<>();

        // Detect signals for both indices
        IndexSignal niftySignal = indexHuntDetector.detectSignal("NIFTY");
        if (niftySignal != null && !isDuplicate(niftySignal)) {
            allSignals.add(niftySignal);
        }

        IndexSignal bankniftySignal = indexHuntDetector.detectSignal("BANKNIFTY");
        if (bankniftySignal != null && !isDuplicate(bankniftySignal)) {
            allSignals.add(bankniftySignal);
        }

        // Apply daily pick ranking (keep only top-quality signals)
        allSignals = applyDailyPickRanking(allSignals);

        // Persist to database
        for (IndexSignal signal : allSignals) {
            indexSignalRepository.save(signal);
            log.info("INDEX_HUNT.signal_saved index={} direction={} quality={} premium_tier={}",
                    signal.getIndexName(),
                    signal.getDirection(),
                    signal.getQualityScore(),
                    isPremiumTier(signal) ? "yes" : "no");
        }

        return allSignals;
    }

    /**
     * Check if signal is a duplicate (same index+direction within DEDUP_MINUTES)
     */
    private boolean isDuplicate(IndexSignal signal) {
        Instant deduplicateAfter = Instant.now().minus(DEDUP_MINUTES, ChronoUnit.MINUTES);

        long recentCount = indexSignalRepository.countRecentSignalsSameDirection(
                signal.getIndexName(),
                signal.getDirection(),
                deduplicateAfter
        );

        if (recentCount > 0) {
            log.debug("INDEX_HUNT.dedup_blocked index={} direction={} reason=recent_signal_exists",
                    signal.getIndexName(), signal.getDirection());
            return true;
        }
        return false;
    }

    /**
     * Apply daily pick ranking: keep only top-ranked signals (1-3 per index)
     * with 36-minute time spacing
     */
    private List<IndexSignal> applyDailyPickRanking(List<IndexSignal> signals) {
        // Group by index
        Map<String, List<IndexSignal>> byIndex = signals.stream()
                .collect(Collectors.groupingBy(IndexSignal::getIndexName));

        List<IndexSignal> ranked = new ArrayList<>();

        for (String indexName : byIndex.keySet()) {
            List<IndexSignal> indexSignals = byIndex.get(indexName);

            // Sort by quality score descending
            indexSignals.sort((a, b) ->
                    b.getQualityScore().compareTo(a.getQualityScore()));

            // Keep top DAILY_PICK_MAX signals with time gap enforcement
            int count = 0;
            for (IndexSignal signal : indexSignals) {
                if (count >= DAILY_PICK_MAX) {
                    break;
                }

                // Check time gap from last added signal
                if (count > 0) {
                    IndexSignal lastAdded = ranked.get(ranked.size() - 1);
                    long gapMinutes = ChronoUnit.MINUTES.between(lastAdded.getTimeDetected(),
                            signal.getTimeDetected());
                    if (gapMinutes < DAILY_PICK_GAP_MIN) {
                        log.debug("INDEX_HUNT.daily_pick_skip index={} reason=insufficient_gap quality={}",
                                indexName, signal.getQualityScore());
                        continue; // Skip this signal, keep looking
                    }
                }

                ranked.add(signal);
                count++;

                log.debug("INDEX_HUNT.daily_pick_selected index={} quality={} rank={}",
                        indexName, signal.getQualityScore(), count);
            }
        }

        return ranked;
    }

    /**
     * Check if signal qualifies as premium tier (quality >= 76)
     */
    private boolean isPremiumTier(IndexSignal signal) {
        return signal.getQualityScore().compareTo(QUALITY_PREMIUM) >= 0;
    }

    /**
     * Get all active pending signals
     */
    public List<IndexSignal> getActivePendingSignals() {
        return indexSignalRepository.findByIsActiveTrueOrderByQualityScoreDesc();
    }

    /**
     * Get signals for specific index
     */
    public List<IndexSignal> getSignalsForIndex(String indexName) {
        return indexSignalRepository.findByIndexNameAndIsActiveTrueOrderByTimeDetectedDesc(indexName);
    }

    /**
     * Get recent signals (last N hours)
     */
    public List<IndexSignal> getRecentSignals(String indexName, int hoursBack) {
        Instant cutoff = Instant.now().minus(hoursBack, ChronoUnit.HOURS);
        return indexSignalRepository.findRecentSignals(indexName, cutoff);
    }

    /**
     * Get high-quality signals (quality >= threshold)
     */
    public List<IndexSignal> getHighQualitySignals(BigDecimal minQuality) {
        return indexSignalRepository.findByQualityScoreGreaterThanEqualOrderByQualityScoreDesc(minQuality);
    }

    /**
     * Mark signal as filled (executed)
     */
    public void markSignalFilled(Long signalId, BigDecimal actualEntryPremium) {
        Optional<IndexSignal> optional = indexSignalRepository.findById(signalId);
        if (optional.isPresent()) {
            IndexSignal signal = optional.get();
            signal.setExecutionStatus("FILLED");
            signal.setFilledAt(Instant.now());
            signal.setActualEntryPremium(actualEntryPremium);
            indexSignalRepository.save(signal);

            log.info("INDEX_HUNT.signal_filled signal_id={} entry_premium={}", signalId, actualEntryPremium);
        }
    }

    /**
     * Mark signal as closed (T1 or SL hit)
     */
    public void markSignalClosed(Long signalId, BigDecimal actualExitPremium,
                                 String outcomeType, BigDecimal totalPnL) {
        Optional<IndexSignal> optional = indexSignalRepository.findById(signalId);
        if (optional.isPresent()) {
            IndexSignal signal = optional.get();
            signal.setExecutionStatus(outcomeType.equals("WIN") ? "T1_HIT" : "SL_HIT");
            signal.setClosedAt(Instant.now());
            signal.setActualExitPremium(actualExitPremium);
            signal.setOutcomeType(outcomeType);
            signal.setTotalPnL(totalPnL);
            signal.setIsActive(false);
            indexSignalRepository.save(signal);

            log.info("INDEX_HUNT.signal_closed signal_id={} outcome={} pnl={}", signalId, outcomeType, totalPnL);
        }
    }

    /**
     * Get daily statistics for today
     */
    public IndexHuntDailyStats getDailyStats() {
        Instant startOfDay = Instant.now().atZone(IST).truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
        List<IndexSignal> closedToday = indexSignalRepository.findClosedSignalsBetween(startOfDay, endOfDay);

        long wins = closedToday.stream()
                .filter(s -> "WIN".equals(s.getOutcomeType()))
                .count();

        long losses = closedToday.stream()
                .filter(s -> "LOSS".equals(s.getOutcomeType()))
                .count();

        BigDecimal totalPnL = closedToday.stream()
                .map(IndexSignal::getTotalPnL)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        double winRate = closedToday.isEmpty() ? 0 :
                (double) wins / (wins + losses) * 100;

        return new IndexHuntDailyStats(
                closedToday.size(),
                wins,
                losses,
                winRate,
                totalPnL
        );
    }

    /**
     * Statistics DTO
     */
    public static class IndexHuntDailyStats {
        public int totalTrades;
        public long wins;
        public long losses;
        public double winRate;
        public BigDecimal totalPnL;

        public IndexHuntDailyStats(int total, long w, long l, double wr, BigDecimal pnl) {
            this.totalTrades = total;
            this.wins = w;
            this.losses = l;
            this.winRate = wr;
            this.totalPnL = pnl;
        }

        @Override
        public String toString() {
            return String.format(
                    "Trades=%d | Wins=%d | Losses=%d | WR=%.1f%% | PnL=%s",
                    totalTrades, wins, losses, winRate, totalPnL
            );
        }
    }
}
