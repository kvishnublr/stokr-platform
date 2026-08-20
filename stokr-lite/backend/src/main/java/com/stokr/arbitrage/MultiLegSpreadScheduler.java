package com.stokr.arbitrage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Box/Vertical/Butterfly/Condor had NO backend scheduled scan -- unlike BidParityService
 * (its own 15s cron + tick-triggered debounced scan), these 4 scanners only ever ran when a
 * browser had that specific strategy's tab open, driving the scan via the frontend's own
 * useQuery polling. Nobody having a tab open for 2 days meant zero new signals for 2 days
 * (confirmed: last scan timestamp for Box Spread matched exactly when someone had last
 * viewed that tab), and -- more importantly -- Auto-Trade for these 4 strategies could only
 * ever fire while a user happened to have the relevant browser tab open, which defeats the
 * entire point of "automatic" trading. This runs the same scan + auto-exec sequence the
 * controller endpoints already do on every page load, on a fixed schedule instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiLegSpreadScheduler {

    private final BoxSpreadService boxSpreadService;
    private final VerticalSpreadService verticalSpreadService;
    private final ButterflySpreadService butterflySpreadService;
    private final CondorSpreadService condorSpreadService;
    private final OptionArbAutoExecService autoExecService;

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

    private void scanAndExec(String label, java.util.function.Supplier<List<Map<String, Object>>> scan) {
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
