package com.stokr.marketdata.chartink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Inserts Chartink alerts directly as signals into the equity_signals table.
 * This ensures Chartink webhook data is immediately available as trading signals.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChartinkSignalInserter {

    private final JdbcTemplate jdbcTemplate;

    public void insertSignalsFromAlert(ChartinkAlert alert) {
        try {
            List<String> symbols = alert.stocks();
            List<BigDecimal> prices = alert.triggerPrices();

            for (int i = 0; i < symbols.size(); i++) {
                String symbol = symbols.get(i);
                BigDecimal price = prices.get(i);

                insertEquitySignal(symbol, price, alert.scanName(), alert.triggeredAt());
            }
        } catch (Exception e) {
            log.error("chartink.signal_insert.error alert={}", alert.scanName(), e);
        }
    }

    private void insertEquitySignal(String symbol, BigDecimal entryPrice, String scanName, Instant detectedAt) {
        String sql = "INSERT INTO equity_signals (" +
                "symbol_name, sector, tier, direction, current_price, entry_level, " +
                "stop_loss_level, target_level1, confidence_level, quality_score, " +
                "execution_status, time_detected, is_active, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try {
            BigDecimal stopLoss = entryPrice.multiply(BigDecimal.valueOf(0.98)); // 2% below entry
            BigDecimal target1 = entryPrice.multiply(BigDecimal.valueOf(1.02)); // 2% above entry

            jdbcTemplate.update(sql,
                    symbol,
                    "TECHNOLOGY",  // Default sector
                    "TIER1",       // Default tier
                    "BUY",         // Chartink alerts are typically buy signals
                    entryPrice,    // current_price
                    entryPrice,    // entry_level
                    stopLoss,      // stop_loss_level (2% below)
                    target1,       // target_level1 (2% above)
                    85,            // confidence_level (high confidence for Chartink)
                    90.0,          // quality_score (high quality)
                    "PENDING",     // execution_status
                    detectedAt,    // time_detected
                    true,          // is_active
                    Instant.now()  // created_at
            );

            log.info("chartink.signal_inserted symbol={} price={} scan={}", symbol, entryPrice, scanName);
        } catch (Exception e) {
            log.error("chartink.signal_insert.failed symbol={} price={}", symbol, entryPrice, e);
        }
    }
}
