package com.stokr.execution.validation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyValidationMetricsRollupScheduler {

    private final StrategyValidationMetricsRollupService rollupService;

    @Scheduled(cron = "${stokr.validation.metrics-rollup.cron:0 40 15 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void rollupToday() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        try {
            rollupService.rollupSession(today);
        } catch (Exception ex) {
            log.error("validation.rollup.scheduler_failed date={} err={}", today, ex.getMessage(), ex);
        }
    }
}
