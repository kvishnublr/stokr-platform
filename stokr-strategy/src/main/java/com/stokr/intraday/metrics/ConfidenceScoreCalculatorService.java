package com.stokr.intraday.metrics;

import com.stokr.intraday.metrics.domain.ConfidenceScore;
import com.stokr.intraday.metrics.dto.OrderFlowSignalEnhancement;
import com.stokr.intraday.metrics.repository.ConfidenceScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "stokr.confidence-strategy.calculator-enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class ConfidenceScoreCalculatorService {

    private final OrderFlowMetricsService metricsService;
    private final ConfidenceScoreRepository scoreRepository;

    // Run every 1 minute to calculate confidence for all Nifty 100 stocks
    @Scheduled(fixedRateString = "${stokr.confidence-strategy.calculator-interval:60000}")
    @Transactional
    public void calculateConfidenceForAllNifty100() {
        try {
            long startTime = System.currentTimeMillis();
            log.info("🔄 Starting confidence calculation for Nifty 100...");

            List<String> nifty100Symbols = getNifty100Symbols();
            int successCount = 0;
            int failureCount = 0;

            for (String symbol : nifty100Symbols) {
                try {
                    // Get the order flow signal enhancement (calculates confidence)
                    OrderFlowSignalEnhancement signal = metricsService.getOrderFlowSignal(symbol);

                    if (signal != null && !Boolean.TRUE.equals(signal.getError())) {
                        // Create confidence score record
                        ConfidenceScore score = ConfidenceScore.builder()
                            .symbol(symbol)
                            .timestamp(Instant.now())
                            .confidenceScore(signal.getConfidence())
                            .buyerPressure(signal.getBuyerPressureScore())
                            .sellerPressure(signal.getSellerPressureScore())
                            .liquidityScore(signal.getLiquidityScore())
                            .signalStrength(signal.getSignalStrength())
                            .build();

                        scoreRepository.save(score);
                        successCount++;

                        log.debug("✅ Calculated confidence for {}: {}%",
                            symbol, signal.getConfidence());
                    } else {
                        failureCount++;
                        log.debug("⚠️  No valid data for {}", symbol);
                    }
                } catch (Exception e) {
                    failureCount++;
                    log.warn("❌ Failed to calculate confidence for symbol: {}", symbol, e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ Confidence calculation complete. " +
                    "Success: {}, Failed: {}, Duration: {}ms",
                    successCount, failureCount, duration);

        } catch (Exception e) {
            log.error("💥 Confidence calculation failed", e);
        }
    }

    private List<String> getNifty100Symbols() {
        // Nifty 100 stocks (can be loaded from configuration or database)
        return Arrays.asList(
            "SBIN", "HDFC", "INFY", "RELIANCE", "TCS",
            "HDFCBANK", "KOTAKBANK", "ICICIBANK", "AXISBANK", "LTIM",
            "WIPRO", "TECHM", "BAJAJFINSV", "BAJAJFINSERV", "ASIANPAINT",
            "MARUTI", "HYUNDAI", "TATAMOTORS", "MRF", "BHARTIARTL",
            "JSWSTEEL", "HINDALCO", "GAIL", "NTPC", "POWERGRID",
            "EICHERMOT", "BOSCHIND", "APOLLOHOSP", "DRREDDY", "SUNPHARMA",
            "CIPLA", "LUPIN", "DIVISLAB", "ALKEM", "NATPHARM",
            "INDIGO", "SPICEJET", "ADANIPORTS", "ADANIENT", "ADANIGREEN",
            "IPCALAB", "KPITTECH", "PERSISTENT", "LTTS", "MINDTREE",
            "INFIBEAM", "MPHASIS", "COFORGE", "CDSL", "NAUKRI",
            "PAGEIND", "SBILIFE", "HCLTECH", "GODREJCP", "BRITANNIA",
            "NESTLEIND", "PGHH", "MARICO", "COLPAL", "BBTC",
            "BIOCON", "PIDILITIND", "SHREECEM", "AMBUJACEMENT", "ULTRACEM",
            "SIEMENS", "ABB", "HAVELLS", "LEGRAND", "SCHNEIDER",
            "BEL", "LAUREUSINF", "HGSSOFT", "CRUDEOIL", "GUNSHIP",
            "INTSOFTWARE", "FINANCIALTECH", "KALYANKJIL", "KALYANKJSE", "SOUTHBANK",
            "FEDERALBANK", "IDFCBANK", "INDUSIND", "BANDHANBNK", "YES",
            "IDFC", "MFSL", "MANAPPURAM", "BAJAJHOUSING", "PNBHOUSING",
            "SBICARD", "HDFCAMC", "MOTILALOPS", "AFFLE"
        );
    }

    public List<ConfidenceScore> getLatestScoresForSymbols(List<String> symbols) {
        return symbols.stream()
            .map(scoreRepository::findLatestBySymbol)
            .filter(opt -> opt.isPresent())
            .map(opt -> opt.get())
            .toList();
    }

    public int getActiveSymbolCount() {
        return (int) scoreRepository.countSymbolsAboveThreshold(0,
            Instant.now().minusSeconds(300));  // Last 5 minutes
    }
}
