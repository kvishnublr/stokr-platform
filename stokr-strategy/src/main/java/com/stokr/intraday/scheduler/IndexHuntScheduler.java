package com.stokr.intraday.scheduler;

import com.stokr.intraday.detector.MarketDataProvider;
import com.stokr.intraday.domain.IndexSignal;
import com.stokr.intraday.service.*;
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
 * LEGACY — disabled by default. Replaced by catalog-driven IndexHuntSignalGenerator.
 * Set stokr.legacy.indexhunt.scheduler.enabled=true to re-enable.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "stokr.legacy.indexhunt.scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class IndexHuntScheduler {

    private final IndexHuntService indexHuntService;
    private final PaperTradingExecutor paperTradingExecutor;
    private final IndexHuntTelegramService telegramService;
    private final MarketDataProvider marketDataProvider;

    private static final int TRADING_START_MIN = 615;   // 10:15 AM
    private static final int TRADING_END_MIN = 825;     // 1:45 PM
    private static final int DAILY_SUMMARY_MIN = 1015;  // 3:35 PM

    public IndexHuntScheduler(IndexHuntService indexHuntService,
                             PaperTradingExecutor paperTradingExecutor,
                             IndexHuntTelegramService telegramService,
                             MarketDataProvider marketDataProvider) {
        this.indexHuntService = indexHuntService;
        this.paperTradingExecutor = paperTradingExecutor;
        this.telegramService = telegramService;
        this.marketDataProvider = marketDataProvider;
    }

    /**
     * Run INDEX HUNT signal detection every 10 seconds
     * Only during 10:15 AM - 1:45 PM IST
     *
     * @Scheduled: Fixed-rate in milliseconds (10000ms = 10 seconds)
     */
    @Scheduled(fixedRate = 10000, initialDelay = 5000)
    public void detectSignals() {
        try {
            // Check if market is open and within trading window
            if (!isWithinTradingWindow()) {
                return;
            }

            log.debug("INDEX_HUNT.scheduler_detect_cycle_start");

            // Run full detection
            List<IndexSignal> signals = indexHuntService.runFullDetection();

            // Send alerts for new signals
            for (IndexSignal signal : signals) {
                try {
                    telegramService.sendSignalAlert(signal);
                } catch (Exception e) {
                    log.error("SCHEDULER.telegram_signal_alert_failed signal_id={} error={}",
                            signal.getSignalId(), e.getMessage());
                }
            }

            if (!signals.isEmpty()) {
                log.info("INDEX_HUNT.scheduler_signals_detected count={}", signals.size());
            }

        } catch (Exception e) {
            log.error("INDEX_HUNT.scheduler_detect_error error={}", e.getMessage());
        }
    }

    /**
     * Monitor active paper trades every 5 seconds
     * Checks for T1/SL hits
     */
    @Scheduled(fixedRate = 5000, initialDelay = 2000)
    public void monitorPaperTrades() {
        try {
            List<PaperTradingExecutor.PaperTrade> active = paperTradingExecutor.getActiveTrades();

            if (active.isEmpty()) {
                return;
            }

            // Fetch current prices for NIFTY and BANKNIFTY
            Map<String, BigDecimal> prices = new HashMap<>();

            MarketDataProvider.IndexMarketData niftyData = marketDataProvider.getIndexMarketData("NIFTY");
            if (niftyData != null) {
                prices.put("NIFTY", niftyData.currentPrice);
            }

            MarketDataProvider.IndexMarketData bnfData = marketDataProvider.getIndexMarketData("BANKNIFTY");
            if (bnfData != null) {
                prices.put("BANKNIFTY", bnfData.currentPrice);
            }

            // Monitor trades
            if (!prices.isEmpty()) {
                paperTradingExecutor.monitorActiveTrades(prices);
                log.debug("INDEX_HUNT.scheduler_monitor_cycle active_trades={}", active.size());
            }

        } catch (Exception e) {
            log.error("INDEX_HUNT.scheduler_monitor_error error={}", e.getMessage());
        }
    }

    /**
     * Send daily summary at 3:35 PM IST (market close)
     */
    @Scheduled(cron = "0 35 15 * * MON-FRI")  // 3:35 PM IST, Mon-Fri
    public void sendDailySummary() {
        try {
            PaperTradingExecutor.PaperTradingStats stats = paperTradingExecutor.getStats();

            if (stats.totalTrades > 0) {
                telegramService.sendDailySummary(stats);
                log.info("INDEX_HUNT.daily_summary_sent trades={} wr={} pnl={}",
                        stats.totalTrades, stats.winRate, stats.totalPnL);
            }

        } catch (Exception e) {
            log.error("INDEX_HUNT.scheduler_summary_error error={}", e.getMessage());
        }
    }

    /**
     * Health check every minute (verify Telegram connection, market data, etc.)
     */
    @Scheduled(fixedRate = 60000)
    public void healthCheck() {
        try {
            boolean telegramOk = telegramService.testConnection();
            boolean marketDataOk = marketDataProvider.isMarketOpen() ||
                    marketDataProvider.getCurrentTimeMinutes() >= 0;

            log.debug("INDEX_HUNT.scheduler_health_check telegram={} marketdata={}",
                    telegramOk, marketDataOk);

        } catch (Exception e) {
            log.warn("INDEX_HUNT.scheduler_health_check_error error={}", e.getMessage());
        }
    }

    /**
     * Check if we're within trading window
     */
    private boolean isWithinTradingWindow() {
        if (!marketDataProvider.isMarketOpen()) {
            return false;
        }

        int timeMin = marketDataProvider.getCurrentTimeMinutes();
        return timeMin >= TRADING_START_MIN && timeMin <= TRADING_END_MIN;
    }

    /**
     * Get current time in IST minutes
     */
    private int getCurrentTimeMinutes() {
        LocalTime ist = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return ist.getHour() * 60 + ist.getMinute();
    }

    /**
     * Manual trigger for signal detection (for testing)
     */
    public void manualDetectSignals() {
        log.info("INDEX_HUNT.manual_detect_requested");
        detectSignals();
    }

    /**
     * Manual trigger for monitoring (for testing)
     */
    public void manualMonitorTrades() {
        log.info("INDEX_HUNT.manual_monitor_requested");
        monitorPaperTrades();
    }

    /**
     * Manual trigger for daily summary (for testing)
     */
    public void manualSendSummary() {
        log.info("INDEX_HUNT.manual_summary_requested");
        sendDailySummary();
    }
}
