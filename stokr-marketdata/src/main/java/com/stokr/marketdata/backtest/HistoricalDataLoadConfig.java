package com.stokr.marketdata.backtest;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for historical data loading for backtesting
 */
@Component
@ConfigurationProperties(prefix = "stokr.backtest")
@Data
public class HistoricalDataLoadConfig {

    /**
     * Lookback days for historical data (default: 1825 = 5 years)
     */
    private int historicalLookbackDays = 1825;

    /**
     * Chunk size for API calls (max 55 days per Zerodha limit)
     */
    private int chunkDays = 55;

    /**
     * Rate limit between API calls (milliseconds)
     */
    private long rateLimitMs = 350;

    /**
     * Enable historical data loading
     */
    private boolean enabled = true;

    /**
     * Symbols to load (empty = all available)
     */
    private java.util.List<String> symbols = new java.util.ArrayList<>();
}
