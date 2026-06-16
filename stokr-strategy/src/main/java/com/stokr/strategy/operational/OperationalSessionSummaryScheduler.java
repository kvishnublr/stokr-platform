package com.stokr.strategy.operational;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class OperationalSessionSummaryScheduler {

    private final OperationalSessionSummaryService summaryService;

    @Scheduled(cron = "${stokr.strategy.session-summary.cron:0 35 15 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void generateDailySummary() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        try {
            summaryService.generateForSession(today);
        } catch (Exception ex) {
            log.error("operational.session_summary.failed date={} error={}", today, ex.getMessage(), ex);
        }
    }
}
