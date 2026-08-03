package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Near-target handler:
 * 1) Prefer smart roll (close CE+PE, keep FUT, open new options) when a ≥₹300 candidate exists
 * 2) Else full exit (options + fut)
 */
@Component
public class BidParityAutoExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(BidParityAutoExitMonitor.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SignalTradeBookService tradeBookService;
    private final OptionArbAutoExecService autoExecService;
    private final OptionArbHistoryService historyService;

    public BidParityAutoExitMonitor(SignalTradeBookService tradeBookService,
                                    OptionArbAutoExecService autoExecService,
                                    OptionArbHistoryService historyService) {
        this.tradeBookService = tradeBookService;
        this.autoExecService = autoExecService;
        this.historyService = historyService;
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
            Map<String, Object> book = tradeBookService.getPositionsBook("BID", false, "BOTH");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> positions = (List<Map<String, Object>>) book.getOrDefault("positions", List.of());

            List<OptionArbOpportunity> recent = historyService.getRepository()
                    .findByScanTimeBetween(LocalDateTime.now().minusMinutes(5), LocalDateTime.now());

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

                OptionArbOpportunity rollCand = null;
                if (autoExecService.isSmartRollEnabled()) {
                    rollCand = findRollCandidate(pos, recent, settings);
                }

                if (rollCand != null) {
                    LivePosition rolled = autoExecService.smartRoll(pos, rollCand, pnl);
                    if (rolled != null) {
                        log.info("SMART-ROLL pos#{} {} {}→{} pnl≈{}", id, pos.getUnderlying(),
                                pos.getStrike(), rollCand.getStrike(), pnl);
                        autoExecService.addLog("ROLL", "OK",
                                "pos#" + id + " → " + rollCand.getStrike()
                                        + " edge=₹" + rollCand.getEdgeAfterCosts());
                        continue;
                    }
                    autoExecService.addLog("ROLL", "FALLBACK",
                            "pos#" + id + " roll failed — full exit");
                }

                boolean brokerOk = autoExecService.closeLiveHedge(pos);
                if (!brokerOk) {
                    log.warn("AUTO-EXIT broker close failed for pos#{} — retry next tick", id);
                    autoExecService.addLog("EXIT", "BLOCKED",
                            "AUTO near-edge pos#" + id + " broker close failed — retry next tick");
                    continue;
                }
                LivePosition closed = tradeBookService.exitPosition(id, pnl,
                        String.format(java.util.Locale.ROOT,
                                "AUTO_EXIT_NEAR_EDGE target=%.2f thr=%.2f pnl=%.2f", target, thr, pnl));
                log.info("AUTO-EXIT near edge: pos#{} {} strike={} exitPnl={}",
                        closed.getId(), closed.getUnderlying(), closed.getStrike(), closed.getCurrentPnl());
                autoExecService.addLog("EXIT", "OK",
                        "AUTO near-edge pos#" + closed.getId() + " pnl=" + closed.getCurrentPnl());
            }
        } catch (Exception e) {
            log.warn("Bid Parity auto-exit/roll tick failed: {}", e.getMessage());
        }
    }

    private OptionArbOpportunity findRollCandidate(LivePosition pos,
                                                   List<OptionArbOpportunity> recent,
                                                   Map<String, Object> settings) {
        if (pos.getUnderlying() == null || pos.getStrike() == null) return null;
        String key = pos.getUnderlying().toLowerCase(java.util.Locale.ROOT);
        final double minEdge = settings.get(key + "MinEdge") instanceof Number n
                ? n.doubleValue() : 300.0;
        double maxEdgeTmp = 800.0;
        try {
            maxEdgeTmp = Double.parseDouble(String.valueOf(
                    settings.getOrDefault(key + "MaxEdge",
                            settings.getOrDefault("max_edge_after_costs", 800.0))));
        } catch (Exception ignored) {}
        final double maxEdge = maxEdgeTmp;

        final String wantAction = OptionArbAutoExecService.normalizeAction(pos.getAction());

        return recent.stream()
                .filter(o -> pos.getUnderlying().equalsIgnoreCase(o.getUnderlying()))
                .filter(o -> o.getStrike() != null && !Objects.equals(o.getStrike(), pos.getStrike()))
                .filter(o -> o.getEdgeAfterCosts() != null)
                .filter(o -> o.getEdgeAfterCosts().doubleValue() >= minEdge)
                .filter(o -> o.getEdgeAfterCosts().doubleValue() <= maxEdge)
                .filter(o -> o.getEdgePoints() == null || o.getEdgePoints().doubleValue() <= 25.0)
                .filter(o -> o.getExpiryDate() != null)
                .filter(o -> {
                    String a = OptionArbAutoExecService.normalizeAction(o.getAction());
                    return wantAction == null || wantAction.equals(a);
                })
                .max(Comparator.comparingDouble(o -> o.getEdgeAfterCosts().doubleValue()))
                .orElse(null);
    }
}
