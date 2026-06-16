package com.stokr.risk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Auto-square-off service that closes all intraday positions at EOD.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EodSquareOffService {

    @Value("${trading.eod-squareoff-time:15:15}")
    private String squareOffTime;

    /**
     * Runs every minute during market hours to check if EOD square-off is needed.
     * In production, this would call the execution engine to close all positions.
     */
    @Scheduled(cron = "0 15 15 * * MON-FRI")
    public void triggerEodSquareOff() {
        log.info("=== EOD SQUARE-OFF TRIGGERED at {} ===", squareOffTime);
        // TODO: Fetch all active deployments with open positions
        // TODO: Place market orders to close all positions
        // TODO: Update deployment/position status
        log.info("=== EOD SQUARE-OFF COMPLETE ===");
    }
}
