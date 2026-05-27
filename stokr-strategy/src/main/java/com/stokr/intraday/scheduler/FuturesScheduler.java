package com.stokr.intraday.scheduler;

import com.stokr.intraday.detector.MarketDataProvider;
import com.stokr.intraday.domain.FuturesSignal;
import com.stokr.intraday.service.FuturesSignalService;
import com.stokr.intraday.service.FuturesTradingExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LEGACY — disabled by default. Replaced by catalog-driven S3VwapRetestSignalGenerator + S7RangeFadeSignalGenerator.
 * Set stokr.legacy.futures.scheduler.enabled=true to re-enable.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "stokr.legacy.futures.scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class FuturesScheduler {

    private final FuturesSignalService futuresSignalService;
    private final FuturesTradingExecutor futuresTradingExecutor;
    private final MarketDataProvider marketDataProvider;

    private static final int TRADING_START_MIN = 615;   // 10:15 AM IST
    private static final int TRADING_END_MIN = 825;     // 1:45 PM IST

    public FuturesScheduler(FuturesSignalService futuresSignalService,
                           FuturesTradingExecutor futuresTradingExecutor,
                           MarketDataProvider marketDataProvider) {
        this.futuresSignalService = futuresSignalService;
        this.futuresTradingExecutor = futuresTradingExecutor;
        this.marketDataProvider = marketDataProvider;
    }

    /**
     * Run S3/S7 detection every 10 seconds during trading window
     */
    @Scheduled(fixedRate = 10000, initialDelay = 5000)
    public void detectFuturesSignals() {
        try {
            if (!isWithinTradingWindow()) {
                return;
            }

            log.debug("FUTURES.scheduler_detect_cycle_start");

            // Run detection
            List<FuturesSignal> signals = futuresSignalService.runFullDetection();

            if (!signals.isEmpty()) {
                log.info("FUTURES.scheduler_signals_detected count={}", signals.size());
            }

        } catch (Exception e) {
            log.error("FUTURES.scheduler_detect_error error={}", e.getMessage());
        }
    }

    /**
     * Monitor active trades every 5 seconds
     */
    @Scheduled(fixedRate = 5000, initialDelay = 2000)
    public void monitorFuturesTrades() {
        try {
            List<FuturesTradingExecutor.FuturesTrade> active = futuresTradingExecutor.getActiveTrades();

            if (active.isEmpty()) {
                return;
            }

            // Get current prices
            Map<String, BigDecimal> prices = new HashMap<>();

            MarketDataProvider.IndexMarketData niftyData = marketDataProvider.getIndexMarketData("NIFTY");
            if (niftyData != null) {
                prices.put("NIFTY", niftyData.currentPrice);
            }

            MarketDataProvider.IndexMarketData bnfData = marketDataProvider.getIndexMarketData("BANKNIFTY");
            if (bnfData != null) {
                prices.put("BANKNIFTY", bnfData.currentPrice);
            }

            if (!prices.isEmpty()) {
                futuresTradingExecutor.monitorActiveTrades(prices);
                log.debug("FUTURES.scheduler_monitor_cycle active_trades={}", active.size());
            }

        } catch (Exception e) {
            log.error("FUTURES.scheduler_monitor_error error={}", e.getMessage());
        }
    }

    /**
     * Send daily summary at 3:35 PM IST
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI")
    public void sendDailySummary() {
        try {
            FuturesTradingExecutor.FuturesTradingStats stats = futuresTradingExecutor.getStats();

            if (stats.totalTrades > 0) {
                log.info("FUTURES.daily_summary trades={} wr={} pnl={}",
                        stats.totalTrades, stats.winRate, stats.totalPnL);
            }

        } catch (Exception e) {
            log.error("FUTURES.scheduler_summary_error error={}", e.getMessage());
        }
    }

    /**
     * Health check every minute
     */
    @Scheduled(fixedRate = 60000)
    public void healthCheck() {
        try {
            boolean marketOpen = marketDataProvider.isMarketOpen();
            log.debug("FUTURES.scheduler_health_check market_open={}", marketOpen);

        } catch (Exception e) {
            log.warn("FUTURES.scheduler_health_check_error error={}", e.getMessage());
        }
    }

    private boolean isWithinTradingWindow() {
        if (!marketDataProvider.isMarketOpen()) {
            return false;
        }

        int timeMin = getCurrentTimeMinutes();
        return timeMin >= TRADING_START_MIN && timeMin <= TRADING_END_MIN;
    }

    private int getCurrentTimeMinutes() {
        LocalTime ist = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return ist.getHour() * 60 + ist.getMinute();
    }
}
