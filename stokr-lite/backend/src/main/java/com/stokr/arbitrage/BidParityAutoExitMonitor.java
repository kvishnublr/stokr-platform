package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Auto-exit Bid Parity live positions when live PnL reaches near the entry edge.
 * Example: entered at edge ₹300 → exit when PnL ≥ ₹290 (buffer ₹10).
 * Independent of auto-exec ENTRY (can exit while entry stays OFF).
 */
@Component
public class BidParityAutoExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(BidParityAutoExitMonitor.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SignalTradeBookService tradeBookService;
    private final OptionArbAutoExecService autoExecService;

    public BidParityAutoExitMonitor(SignalTradeBookService tradeBookService,
                                    OptionArbAutoExecService autoExecService) {
        this.tradeBookService = tradeBookService;
        this.autoExecService = autoExecService;
    }

    @Scheduled(fixedDelayString = "${option-arb.auto-exit-interval:5000}", initialDelay = 15000)
    public void tick() {
        Map<String, Object> settings = autoExecService.getSettings();
        if (!Boolean.TRUE.equals(settings.get("bidParityAutoExitEnabled"))) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 32))) return;

        double buffer = 10.0;
        Object bufObj = settings.getOrDefault("bidParityExitNearBuffer", 10.0);
        try {
            buffer = Double.parseDouble(String.valueOf(bufObj));
        } catch (Exception ignored) {}
        if (buffer < 0) buffer = 0;
        if (buffer > 500) buffer = 500;

        try {
            List<LivePosition> exited = tradeBookService.autoExitNearTargetEdge("BID", buffer);
            for (LivePosition p : exited) {
                log.info("AUTO-EXIT near edge: pos#{} {} {} strike={} targetEdge={} exitPnl={}",
                        p.getId(), p.getUnderlying(), p.getAction(), p.getStrike(),
                        p.getTargetEdge(), p.getCurrentPnl());
                autoExecService.addLog("EXIT", "OK",
                        "AUTO near-edge pos#" + p.getId() + " pnl=" + p.getCurrentPnl());
            }
        } catch (Exception e) {
            log.warn("Bid Parity auto-exit tick failed: {}", e.getMessage());
        }
    }
}
