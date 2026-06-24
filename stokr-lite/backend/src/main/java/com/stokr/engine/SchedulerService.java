package com.stokr.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final ExecutionEngine executionEngine;

    @Value("${trading.scan-interval-seconds:60}")
    private int scanIntervalSeconds;

    /**
     * Main market scan - runs every minute during IST market hours.
     */
    @Scheduled(cron = "0 */1 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void marketScan() {
        executionEngine.runScanCycle();
    }
}
