package com.stokr.marketdata.tick;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily database hygiene & maintenance service.
 * Executes cleanup_tick_tables() every night at 01:00 AM IST to purge old raw tick anomalies
 * and candles, preventing unbounded database growth and maintaining high disk IOPS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseMaintenanceScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Kolkata")
    public void runDailyCleanup() {
        log.info("=== STARTING DAILY DATABASE HYGIENE MAINTENANCE ===");
        long startMs = System.currentTimeMillis();
        try {
            jdbcTemplate.execute("SELECT cleanup_tick_tables();");
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("=== DAILY DATABASE HYGIENE FINISHED (elapsed={}ms) ===", elapsed);
        } catch (Exception e) {
            log.error("Daily database maintenance failed: {}", e.getMessage(), e);
        }
    }
}