package com.stokr.execution.safety;

import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
public class OmsSafetyScheduler {

    private final MarketCloseProtectionService marketCloseProtectionService;
    private final TradingKillSwitchService killSwitchService;
    private final StrategySignalRepository signalRepository;

    @Scheduled(cron = "${stokr.oms.market-close.flatten-cron:0 20 15 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void nseMarketCloseFlatten() {
        triggerFlattenIfDue("NSE");
    }

    @Scheduled(cron = "${stokr.oms.market-close.mcx-flatten-cron:0 50 23 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void mcxMarketCloseFlatten() {
        triggerFlattenIfDue("MCX");
    }

    /** Re-arm LIVE execution after overnight market-close kill switch (MON???FRI NSE open). */
    @Scheduled(cron = "${stokr.oms.market-open.disarm-cron:0 10 9 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void nseMarketOpenDisarmKillSwitch() {
        disarmIfMarketCloseEngaged("Scheduled NSE market-open re-arm");
    }

    /**
     * The NSE flatten at 15:20 engages the kill switch, but the MCX evening session runs
     * ~17:00???23:30 ??? without this re-arm every MCX evening signal is rejected with
     * "Kill switch enabled" (29 rejections on 2026-06-12 alone). MCX close at 23:50
     * re-engages it for the overnight window.
     */
    @Scheduled(cron = "${stokr.oms.mcx-evening.disarm-cron:0 0 17 * * MON-FRI}", zone = "${stokr.strategy.session.zone:Asia/Kolkata}")
    public void mcxEveningDisarmKillSwitch() {
        disarmIfMarketCloseEngaged("Scheduled MCX evening-session re-arm");
    }

    /** Only releases MARKET_CLOSE engagements ??? a manual/risk kill switch always stays engaged. */
    private void disarmIfMarketCloseEngaged(String reason) {
        if (!killSwitchService.isActive()) {
            return;
        }
        var last = killSwitchService.lastEvent();
        if (last.isEmpty() || !last.get().isActive()) {
            return;
        }
        if (!TradingKillSwitchService.TriggerSource.MARKET_CLOSE.name().equals(last.get().getTriggerSource())) {
            return;
        }
        log.info("oms.kill_switch.scheduled_disarm reason={} engagedFor={}", reason, last.get().getReason());
        killSwitchService.deactivate(
                TradingKillSwitchService.TriggerSource.MARKET_CLOSE,
                reason,
                "scheduler");
    }

    private void triggerFlattenIfDue(String segment) {
        Instant now = Instant.now();
        boolean due = "MCX".equals(segment)
                ? marketCloseProtectionService.shouldFlatten(now, "MCX:CRUDEOIL", "COMMODITIES_E2E_TEST")
                : marketCloseProtectionService.shouldFlatten(now, "NSE:RELIANCE", "GAP_FILL");
        if (!due) {
            return;
        }
        log.warn("oms.market_close.flatten_trigger segment={} at={}", segment, now.atZone(ZoneId.of("Asia/Kolkata")));
        killSwitchService.activate(
                TradingKillSwitchService.TriggerSource.MARKET_CLOSE,
                "Scheduled " + segment + " market-close flatten",
                marketCloseProtectionService.forceCloseStalePositionsEnabled(),
                "scheduler");

        if (marketCloseProtectionService.forceCloseStalePositionsEnabled()) {
            long stale = signalRepository.count();
            log.info("oms.market_close.stale_signals_checked segment={} count={}", segment, stale);
        }
    }
}
