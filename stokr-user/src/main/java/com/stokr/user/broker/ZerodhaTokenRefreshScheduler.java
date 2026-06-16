package com.stokr.user.broker;

import com.stokr.user.config.ZerodhaBrokerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Keeps platform and trader Zerodha OAuth sessions renewed without manual admin intervention.
 * Requires a stored refresh token from at least one prior successful OAuth (admin platform or trader connect).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZerodhaTokenRefreshScheduler {

    private final PlatformMarketFeedService platformMarketFeedService;
    private final ZerodhaBrokerProperties zerodhaBrokerProperties;

    @Value("${stokr.broker.zerodha.token-refresh-enabled:true}")
    private boolean refreshEnabled;

    /** Renew when expiry is within this window (default 4h). */
    @Value("${stokr.broker.zerodha.token-refresh-before-hours:4}")
    private int refreshBeforeHours;

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        if (!refreshEnabled || !zerodhaBrokerProperties.isConfigured()) {
            return;
        }
        runRefresh("startup");
    }

    /** Daily pre-market renewal — 05:45 IST, before Kite day-token rollover (~06:00 IST). */
    @Scheduled(cron = "${stokr.broker.zerodha.token-refresh-daily-cron:0 45 5 * * *}", zone = "Asia/Kolkata")
    public void refreshDailyPreMarket() {
        if (!refreshEnabled || !zerodhaBrokerProperties.isConfigured()) {
            return;
        }
        runRefresh("daily-pre-market");
    }

    /** Safety net between daily renewals — default every 30 minutes. */
    @Scheduled(fixedDelayString = "${stokr.broker.zerodha.token-refresh-interval-ms:1800000}")
    public void refreshPeriodicSafetyNet() {
        if (!refreshEnabled || !zerodhaBrokerProperties.isConfigured()) {
            return;
        }
        runRefresh("periodic");
    }

    private void runRefresh(String trigger) {
        try {
            Map<String, Object> summary = platformMarketFeedService.refreshAllZerodhaTokens(
                    Duration.ofHours(Math.max(1, refreshBeforeHours))
            );
            log.info("zerodha.token_refresh trigger={} summary={}", trigger, summary);
        } catch (Exception ex) {
            log.warn("zerodha.token_refresh_failed trigger={} {}", trigger, ex.toString());
        }
    }
}
