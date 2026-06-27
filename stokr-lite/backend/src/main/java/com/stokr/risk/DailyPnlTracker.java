package com.stokr.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory daily realized P&L tracker per deployment.
 * Reset at 9:14 IST before each trading day so MaxDailyLossRule gets fresh numbers.
 * Updated by ExitManager whenever a position closes.
 */
@Slf4j
@Service
public class DailyPnlTracker {

    private final ConcurrentHashMap<Long, BigDecimal> dailyPnl = new ConcurrentHashMap<>();

    public void addPnl(Long deploymentId, BigDecimal pnl) {
        dailyPnl.merge(deploymentId, pnl, BigDecimal::add);
        log.debug("DailyPnl deployment {}: added {} → total {}", deploymentId, pnl, dailyPnl.get(deploymentId));
    }

    public BigDecimal getTodayPnl(Long deploymentId) {
        return dailyPnl.getOrDefault(deploymentId, BigDecimal.ZERO);
    }

    @Scheduled(cron = "0 14 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void resetAtMarketOpen() {
        log.info("Resetting daily P&L tracker for new trading day — {} entries cleared", dailyPnl.size());
        dailyPnl.clear();
    }
}
