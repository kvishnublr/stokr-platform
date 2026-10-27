package com.stokr.arbitrage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class MultiLegSpreadScheduler {

    private final BoxSpreadService boxSpreadService;
    private final VerticalSpreadService verticalSpreadService;
    private final ButterflySpreadService butterflySpreadService;
    private final CondorSpreadService condorSpreadService;
    private final CalendarSpreadService calendarSpreadService;
    private final OptionArbitrageController arbController;
    private final OptionArbAutoExecService autoExecService;
    private final MarketRegimeService marketRegimeService;

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spot-delta-spread-scan");
        t.setDaemon(true);
        return t;
    });

    public MultiLegSpreadScheduler(BoxSpreadService boxSpreadService,
                                    VerticalSpreadService verticalSpreadService,
                                    ButterflySpreadService butterflySpreadService,
                                    CondorSpreadService condorSpreadService,
                                    CalendarSpreadService calendarSpreadService,
                                    @Lazy OptionArbitrageController arbController,
                                    OptionArbAutoExecService autoExecService,
                                    MarketRegimeService marketRegimeService) {
        this.boxSpreadService = boxSpreadService;
        this.verticalSpreadService = verticalSpreadService;
        this.butterflySpreadService = butterflySpreadService;
        this.condorSpreadService = condorSpreadService;
        this.calendarSpreadService = calendarSpreadService;
        this.arbController = arbController;
        this.autoExecService = autoExecService;
        this.marketRegimeService = marketRegimeService;
    }

    // High-frequency sub-second RAM scanning (5s heartbeat cadence, 0 REST API calls)
    @Scheduled(cron = "0/5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanButterfly() {
        scanAndExec("butterfly-spread", () -> butterflySpreadService.scanButterflySpread("ALL"));
    }

    @Scheduled(cron = "1/5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanVertical() {
        scanAndExec("vertical-spread", () -> verticalSpreadService.scanVerticalSpread("ALL"));
    }

    @Scheduled(cron = "2/5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanIronCondor() {
        // Expiry-Day Focus: Iron Condor is strictly permitted only on Expiry Days (Wed for BankNifty, Thu for Nifty)
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.DayOfWeek day = today.getDayOfWeek();
        if (day != java.time.DayOfWeek.WEDNESDAY && day != java.time.DayOfWeek.THURSDAY) {
            return; // Suppressed on Mon, Tue, Fri to prevent multi-day directional drift losses
        }
        scanAndExec("iron-condor", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (String u : List.of("NIFTY", "BANKNIFTY")) {
                if (!marketRegimeService.isStrategyAllowed("IRON_CONDOR", u)) {
                    continue; // Skip Iron Condor during trending or shock regimes
                }
                try {
                    all.addAll(arbController.scanIronCondorForUnderlying(u));
                } catch (Exception e) {
                    log.debug("Iron condor scan failed for {}: {}", u, e.getMessage());
                }
            }
            return all;
        });
    }

    @Scheduled(cron = "3/5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanBox() {
        scanAndExec("box-spread", () -> boxSpreadService.scanBoxSpread("ALL"));
    }

    @Scheduled(cron = "4/5 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanCalendar() {
        scanAndExec("calendar-spread", () -> calendarSpreadService.scanCalendarSpreads("ALL"));
        scanAndExec("condor-spread", () -> condorSpreadService.scanCondorSpread("ALL"));
    }

    /**
     * Event-driven trigger: called the exact millisecond spot price moves significantly.
     */
    public void triggerImmediateScan(String underlying) {
        scanExecutor.submit(() -> {
            try {
                java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
                if (!com.stokr.marketdata.MarketCalendar.isTradingDayToday() || nowIST.isBefore(java.time.LocalTime.of(9, 30)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
                    return;
                }
                // If trending, evaluate directional spreads immediately
                if (!marketRegimeService.isStrategyAllowed("IRON_CONDOR", underlying)) {
                    scanAndExec("event-vertical-" + underlying, () -> verticalSpreadService.scanVerticalSpread(underlying));
                } else {
                    scanIronCondor();
                    scanButterfly();
                }
            } catch (Exception e) {
                log.debug("Immediate event-driven scan failed for {}: {}", underlying, e.getMessage());
            }
        });
    }

    private void scanAndExec(String label, java.util.function.Supplier<List<Map<String, Object>>> scan) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (!com.stokr.marketdata.MarketCalendar.isTradingDayToday() || nowIST.isBefore(java.time.LocalTime.of(9, 30)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
            return;
        }
        try {
            List<Map<String, Object>> opps = scan.get();
            if (opps != null && !opps.isEmpty()) {
                autoExecService.evaluateAndExecuteFromMaps(opps);
            }
        } catch (Exception e) {
            log.error("Scheduled {} scan failed: {}", label, e.getMessage(), e);
        }
    }
}