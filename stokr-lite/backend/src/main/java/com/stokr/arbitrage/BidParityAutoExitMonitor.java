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
            // Preview candidates then broker-close live ones before marking EXITED
            Map<String, Object> book = tradeBookService.getPositionsBook("BID", false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> positions = (List<Map<String, Object>>) book.getOrDefault("positions", List.of());
            for (Map<String, Object> row : positions) {
                Object idObj = row.get("id");
                if (!(idObj instanceof Number n)) continue;
                long id = n.longValue();
                double target = row.get("targetEdge") instanceof Number t ? t.doubleValue() : 0;
                double pnl = row.get("currentPnl") instanceof Number p ? p.doubleValue()
                        : row.get("pnl") instanceof Number p2 ? p2.doubleValue() : 0;
                if (target <= 0) continue;
                double thr = SignalTradeBookService.autoExitThreshold(target, buffer);
                if (pnl + 1e-9 < thr) continue;

                LivePosition pos = tradeBookService.findPosition(id);
                if (pos == null) continue;
                boolean brokerOk = autoExecService.closeLiveHedge(pos);
                if (!brokerOk) {
                    log.warn("AUTO-EXIT broker close failed for pos#{} — not marking EXITED yet", id);
                    autoExecService.addLog("EXIT", "BLOCKED",
                            "AUTO near-edge pos#" + id + " broker close failed — retry next tick");
                    continue;
                }
                LivePosition closed = tradeBookService.exitPosition(id, pnl,
                        String.format(java.util.Locale.ROOT,
                                "AUTO_EXIT_NEAR_EDGE target=%.2f thr=%.2f pnl=%.2f", target, thr, pnl));
                log.info("AUTO-EXIT near edge: pos#{} {} {} strike={} targetEdge={} exitPnl={}",
                        closed.getId(), closed.getUnderlying(), closed.getAction(), closed.getStrike(),
                        closed.getTargetEdge(), closed.getCurrentPnl());
                autoExecService.addLog("EXIT", "OK",
                        "AUTO near-edge pos#" + closed.getId() + " pnl=" + closed.getCurrentPnl());
            }
        } catch (Exception e) {
            log.warn("Bid Parity auto-exit tick failed: {}", e.getMessage());
        }
    }
}
