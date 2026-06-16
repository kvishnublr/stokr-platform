package com.stokr.intraday.service;

import com.stokr.intraday.detector.*;
import com.stokr.intraday.domain.CurrentSetup;
import com.stokr.intraday.domain.NseStock;
import com.stokr.intraday.engine.MarketRegimeDetector;
import com.stokr.intraday.engine.ProbabilityAdjustmentEngine;
import com.stokr.intraday.engine.SetupRankingEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates all setup detection, probability adjustment, and ranking
 * Processes one stock at a time through all 4 detectors
 * Ranks results by quality score
 */
@Service
@Slf4j
public class SetupDetectionService {

    private final GapFillDetector gapFillDetector;
    private final VwapBounceDetector vwapBounceDetector;
    private final SectorLaggardDetector sectorLaggardDetector;
    private final EarlyBreakoutDetector earlyBreakoutDetector;
    private final MarketRegimeDetector marketRegimeDetector;
    private final ProbabilityAdjustmentEngine probabilityEngine;
    private final SetupRankingEngine rankingEngine;

    public SetupDetectionService(
            GapFillDetector gapFillDetector,
            VwapBounceDetector vwapBounceDetector,
            SectorLaggardDetector sectorLaggardDetector,
            EarlyBreakoutDetector earlyBreakoutDetector,
            MarketRegimeDetector marketRegimeDetector,
            ProbabilityAdjustmentEngine probabilityEngine,
            SetupRankingEngine rankingEngine) {
        this.gapFillDetector = gapFillDetector;
        this.vwapBounceDetector = vwapBounceDetector;
        this.sectorLaggardDetector = sectorLaggardDetector;
        this.earlyBreakoutDetector = earlyBreakoutDetector;
        this.marketRegimeDetector = marketRegimeDetector;
        this.probabilityEngine = probabilityEngine;
        this.rankingEngine = rankingEngine;
    }

    /**
     * Process one stock through all detectors
     * Returns list of detected setups for this stock
     *
     * @param stock Stock reference data
     * @param currentPrice Current market price
     * @param currentVwap Current VWAP
     * @param openPrice Day's open price
     * @param prevClose Previous close price
     * @param volume Current volume
     * @param avgVolume 20-day average volume
     * @param atr14 14-period ATR
     * @param hourOfDay Current hour (9-15)
     * @param regime Current market regime
     * @param sampleSize Historical sample size for this setup type
     * @return List of detected setups, sorted by quality score
     */
    public List<CurrentSetup> detectSetups(
            NseStock stock,
            BigDecimal currentPrice,
            BigDecimal currentVwap,
            BigDecimal openPrice,
            BigDecimal prevClose,
            Long volume,
            Long avgVolume,
            BigDecimal atr14,
            Integer hourOfDay,
            MarketRegimeDetector.MarketRegime regime,
            Integer sampleSize) {

        List<CurrentSetup> detectedSetups = new ArrayList<>();
        Instant now = Instant.now();

        // Run all 4 detectors
        CurrentSetup gapFill = gapFillDetector.detectSetup(
                stock, currentPrice, currentVwap, openPrice, prevClose,
                volume, avgVolume, atr14, hourOfDay, now
        );
        if (gapFill != null) {
            detectedSetups.add(gapFill);
        }

        CurrentSetup vwapBounce = vwapBounceDetector.detectSetup(
                stock, currentPrice, currentVwap, openPrice, prevClose,
                volume, avgVolume, atr14, hourOfDay, now
        );
        if (vwapBounce != null) {
            detectedSetups.add(vwapBounce);
        }

        CurrentSetup sectorLaggard = sectorLaggardDetector.detectSetup(
                stock, currentPrice, currentVwap, openPrice, prevClose,
                volume, avgVolume, atr14, hourOfDay, now
        );
        if (sectorLaggard != null) {
            detectedSetups.add(sectorLaggard);
        }

        CurrentSetup earlyBreakout = earlyBreakoutDetector.detectSetup(
                stock, currentPrice, currentVwap, openPrice, prevClose,
                volume, avgVolume, atr14, hourOfDay, now
        );
        if (earlyBreakout != null) {
            detectedSetups.add(earlyBreakout);
        }

        // Enrich with market regime and probability adjustments
        for (CurrentSetup setup : detectedSetups) {
            enrichSetup(setup, regime, sampleSize);
        }

        // Sort by quality score (highest first)
        detectedSetups.sort(Comparator.comparing(CurrentSetup::getQualityScore).reversed());

        if (!detectedSetups.isEmpty()) {
            log.debug("detection.stock={} setups_found={} top_score={}",
                    stock.getStockId(),
                    detectedSetups.size(),
                    detectedSetups.get(0).getQualityScore()
            );
        }

        return detectedSetups;
    }

    /**
     * Enrich setup with market regime and quality score
     */
    private void enrichSetup(
            CurrentSetup setup,
            MarketRegimeDetector.MarketRegime regime,
            Integer sampleSize) {

        setup.setMarketRegime(regime.name());

        // Get base probability (would come from historical_win_rates table)
        BigDecimal baseProbability = getBaseProbability(setup.getSetupType());

        // Adjust for market conditions
        BigDecimal adjustedProbability = probabilityEngine.adjustProbability(
                baseProbability,
                regime,
                setup.getSetupType(),
                BigDecimal.ZERO, // Would come from sector_tracking
                null,            // Would come from recent user trades
                getCurrentHour()
        );

        setup.setBaseProbability(baseProbability);
        setup.setAdjustedProbability(adjustedProbability);

        // Calculate quality score
        BigDecimal qualityScore = rankingEngine.calculateQualityScore(
                setup,
                adjustedProbability,
                getAvgWinPercent(setup.getSetupType()),
                getAvgLossPercent(setup.getSetupType()),
                sampleSize
        );

        setup.setQualityScore(qualityScore);
        setup.setConfidenceLevel(probabilityEngine.getConfidenceLevel(sampleSize));
    }

    /**
     * Get base probability for setup type from historical data
     * In production, would query historical_win_rates table
     */
    private BigDecimal getBaseProbability(String setupType) {
        return switch (setupType) {
            case "gap_fill" -> BigDecimal.valueOf(0.82);           // 82%
            case "vwap_bounce" -> BigDecimal.valueOf(0.71);        // 71%
            case "sector_laggard" -> BigDecimal.valueOf(0.73);     // 73%
            case "early_breakout" -> BigDecimal.valueOf(0.68);     // 68%
            default -> BigDecimal.valueOf(0.70);                   // Default
        };
    }

    /**
     * Get average win percentage for setup type
     * In production, would come from historical_win_rates table
     */
    private BigDecimal getAvgWinPercent(String setupType) {
        return switch (setupType) {
            case "gap_fill" -> BigDecimal.valueOf(0.025);          // 2.5%
            case "vwap_bounce" -> BigDecimal.valueOf(0.020);       // 2.0%
            case "sector_laggard" -> BigDecimal.valueOf(0.022);    // 2.2%
            case "early_breakout" -> BigDecimal.valueOf(0.023);    // 2.3%
            default -> BigDecimal.valueOf(0.02);                   // Default
        };
    }

    /**
     * Get average loss percentage for setup type
     * In production, would come from historical_win_rates table
     */
    private BigDecimal getAvgLossPercent(String setupType) {
        return switch (setupType) {
            case "gap_fill" -> BigDecimal.valueOf(-0.015);         // -1.5%
            case "vwap_bounce" -> BigDecimal.valueOf(-0.012);      // -1.2%
            case "sector_laggard" -> BigDecimal.valueOf(-0.013);   // -1.3%
            case "early_breakout" -> BigDecimal.valueOf(-0.014);   // -1.4%
            default -> BigDecimal.valueOf(-0.01);                  // Default
        };
    }

    /**
     * Get current hour of trading day (9-15)
     */
    private Integer getCurrentHour() {
        return java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).getHour();
    }

    /**
     * Batch process multiple stocks
     * Returns all detected setups from all stocks, ranked by quality
     *
     * @param stocks List of stocks to process
     * @param marketData Map of stock_id -> market data (price, volume, etc.)
     * @param regime Current market regime
     * @return All detected setups, ranked by quality score
     */
    public List<CurrentSetup> detectAllSetups(
            List<NseStock> stocks,
            Map<String, MarketData> marketData,
            MarketRegimeDetector.MarketRegime regime) {

        return stocks.parallelStream()
                .flatMap(stock -> {
                    MarketData data = marketData.get(stock.getStockId());
                    if (data == null) {
                        return java.util.stream.Stream.empty();
                    }

                    return detectSetups(
                            stock,
                            data.currentPrice,
                            data.currentVwap,
                            data.openPrice,
                            stock.getPrevClose(),
                            data.volume,
                            stock.getAverageVolumeDaily(),
                            data.atr14,
                            getCurrentHour(),
                            regime,
                            100 // Default sample size
                    ).stream();
                })
                .sorted(Comparator.comparing((CurrentSetup s) -> s.getQualityScore()).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Get top N setups across all stocks
     */
    public List<CurrentSetup> getTopSetups(
            List<NseStock> stocks,
            Map<String, MarketData> marketData,
            MarketRegimeDetector.MarketRegime regime,
            int limit) {

        List<CurrentSetup> allSetups = detectAllSetups(stocks, marketData, regime);
        return rankingEngine.getTopSetups(allSetups, limit);
    }

    /**
     * Market data DTO for passing intraday OHLCV data
     */
    public static class MarketData {
        public BigDecimal currentPrice;
        public BigDecimal currentVwap;
        public BigDecimal openPrice;
        public Long volume;
        public BigDecimal atr14;

        public MarketData(BigDecimal currentPrice, BigDecimal currentVwap,
                         BigDecimal openPrice, Long volume, BigDecimal atr14) {
            this.currentPrice = currentPrice;
            this.currentVwap = currentVwap;
            this.openPrice = openPrice;
            this.volume = volume;
            this.atr14 = atr14;
        }
    }
}
