package com.stokr.execution.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Auto-exit system: places real exit orders at market close.
 * NSE: 3:00 PM | MCX: 11:55 PM | Currency: separate config
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCloseExitSignalGenerator {

    private final PositionExitOrchestratorService positionExitOrchestratorService;
    private static final UUID PRIMARY_TRADER_ID = UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");

    @Scheduled(cron = "${stokr.strategy.market-close.nse-exit-cron:0 55 14 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    @Transactional
    public void generateNseMarketCloseExits() {
        generateExitSignalForSegment("NSE", LocalTime.of(14, 55), LocalTime.of(15, 0));
    }

    @Scheduled(cron = "${stokr.strategy.market-close.mcx-exit-cron:0 45 23 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    @Transactional
    public void generateMcxMarketCloseExits() {
        generateExitSignalForSegment("MCX", LocalTime.of(23, 45), LocalTime.of(23, 55));
    }

    private void generateExitSignalForSegment(String segment, LocalTime startTime, LocalTime endTime) {
        LocalTime now = Instant.now().atZone(ZoneId.of("Asia/Kolkata")).toLocalTime();

        if (now.isBefore(startTime) || !now.isBefore(endTime)) {
            return;
        }

        try {
            var result = positionExitOrchestratorService.flattenSegment(
                    PRIMARY_TRADER_ID,
                    segment,
                    "MARKET_CLOSE_AUTO_EXIT",
                    "AUTO-EXIT: Market close for " + segment + " at " + now
            );
            log.warn("market_close.auto_exit_executed segment={} time={} ordersCreated={} notes={}",
                    segment, now, result.get("ordersCreated"), result.get("notes"));
        } catch (Exception e) {
            log.error("market_close.auto_exit_failed segment={} error={}", segment, e.getMessage());
        }
    }
}
