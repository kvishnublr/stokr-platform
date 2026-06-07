package com.stokr.bootstrap.automation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PreMarketAutomationScheduler {

    private final PlatformAutomationProperties properties;
    private final PlatformAutomationService automationService;

    @Scheduled(cron = "${stokr.platform.automation.pre-market-cron:0 40 5 * * MON-FRI}", zone = "Asia/Kolkata")
    public void preMarket() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            automationService.runPreMarket();
        } catch (Exception ex) {
            log.warn("platform.automation scheduler_pre_market_error {}", ex.toString());
        }
    }

    @Scheduled(cron = "${stokr.platform.automation.pre-open-cron:0 55 8 * * MON-FRI}", zone = "Asia/Kolkata")
    public void preOpen() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            automationService.runPreOpen();
        } catch (Exception ex) {
            log.warn("platform.automation scheduler_pre_open_error {}", ex.toString());
        }
    }

    @Scheduled(cron = "${stokr.platform.automation.in-session-cron:0 */30 9-16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void inSession() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            automationService.runInSessionMaintenance();
        } catch (Exception ex) {
            log.warn("platform.automation scheduler_in_session_error {}", ex.toString());
        }
    }
}
