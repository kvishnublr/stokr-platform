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
