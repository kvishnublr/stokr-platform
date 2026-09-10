package com.stokr.arbitrage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public MultiLegSpreadScheduler(BoxSpreadService boxSpreadService,
                                    VerticalSpreadService verticalSpreadService,
                                    ButterflySpreadService butterflySpreadService,
                                    CondorSpreadService condorSpreadService,
                                    CalendarSpreadService calendarSpreadService,
                                    @Lazy OptionArbitrageController arbController,
                                    OptionArbAutoExecService autoExecService) {
        this.boxSpreadService = boxSpreadService;
        this.verticalSpreadService = verticalSpreadService;
        this.butterflySpreadService = butterflySpreadService;
        this.condorSpreadService = condorSpreadService;
        this.calendarSpreadService = calendarSpreadService;
        this.arbController = arbController;
        this.autoExecService = autoExecService;
    }

    @Scheduled(cron = "10/20 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanBox() {
        scanAndExec("box-spread", () -> boxSpreadService.scanBoxSpread("ALL"));
    }

    @Scheduled(cron = "15/20 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanVertical() {
        scanAndExec("vertical-spread", () -> verticalSpreadService.scanVerticalSpread("ALL"));
    }

    @Scheduled(cron = "0/20 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanButterfly() {
        scanAndExec("butterfly-spread", () -> butterflySpreadService.scanButterflySpread("ALL"));
    }

    @Scheduled(cron = "5/20 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanCondor() {
        scanAndExec("condor-spread", () -> condorSpreadService.scanCondorSpread("ALL"));
    }

    @Scheduled(cron = "18/20 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanCalendar() {
        scanAndExec("calendar-spread", () -> calendarSpreadService.scanCalendarSpreads("ALL"));
    }

    @Scheduled(cron = "12/20 * 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scanIronCondor() {
        scanAndExec("iron-condor", () -> {
            List<Map<String, Object>> all = new ArrayList<>();
            for (String u : List.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) {
                try {
                    all.addAll(arbController.scanIronCondorForUnderlying(u));
                } catch (Exception e) {
                    log.debug("Iron condor scan failed for {}: {}", u, e.getMessage());
                }
            }
            return all;
        });
    }

    private void scanAndExec(String label, java.util.function.Supplier<List<Map<String, Object>>> scan) {
        java.time.LocalTime nowIST = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (nowIST.isBefore(java.time.LocalTime.of(9, 15)) || nowIST.isAfter(java.time.LocalTime.of(15, 30))) {
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
