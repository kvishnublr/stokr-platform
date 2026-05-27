package com.stokr.intraday.scheduler;

import com.stokr.intraday.service.ADVCashService;
import com.stokr.intraday.service.EquityCashTradingExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class ADVCashScheduler {

    private final ADVCashService advCashService;
    private final EquityCashTradingExecutor equityCashTradingExecutor;

    public ADVCashScheduler(ADVCashService advCashService,
                          EquityCashTradingExecutor equityCashTradingExecutor) {
        this.advCashService = advCashService;
        this.equityCashTradingExecutor = equityCashTradingExecutor;
    }

    @Scheduled(fixedRate = 10000)
    public void detectSignals() {
        try {
            if (!isWithinTradingWindow()) {
                return;
            }

            advCashService.runFullDetection();
            log.info("ADV_CASH.scheduler_detect_complete");

        } catch (Exception e) {
            log.error("ADV_CASH.scheduler_detect_error error={}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 5000)
    public void monitorEquityCashTrades() {
        try {
            if (!isWithinTradingWindow()) {
                return;
            }

            Map<String, BigDecimal> currentPrices = new HashMap<>();
            equityCashTradingExecutor.monitorActiveTrades(currentPrices);
            log.debug("ADV_CASH.scheduler_monitor_complete");

        } catch (Exception e) {
            log.error("ADV_CASH.scheduler_monitor_error error={}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI")
    public void sendDailySummary() {
        try {
            var stats = equityCashTradingExecutor.getStats();
            log.info("ADV_CASH.daily_summary total_trades={} wins={} losses={} win_rate={} pnl={}",
                    stats.totalTrades, stats.wins, stats.losses, stats.winRate, stats.totalPnL);

        } catch (Exception e) {
            log.error("ADV_CASH.scheduler_summary_error error={}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void healthCheck() {
        try {
            int activeTrades = equityCashTradingExecutor.getActiveTrades().size();
            log.debug("ADV_CASH.health_check active_trades={}", activeTrades);

        } catch (Exception e) {
            log.error("ADV_CASH.health_check_error error={}", e.getMessage());
        }
    }

    private boolean isWithinTradingWindow() {
        LocalTime ist = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        int currentMin = ist.getHour() * 60 + ist.getMinute();
        return currentMin >= 570 && currentMin <= 900;
    }
}
