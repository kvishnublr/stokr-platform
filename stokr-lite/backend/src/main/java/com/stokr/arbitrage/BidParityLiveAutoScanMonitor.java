package com.stokr.arbitrage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dual-track Bid Parity auto scan:
 * - PAPER track (default 1 set) always when paperAutoEnabled
 * - LIVE track when enabled && broker != PAPER
 * Both can run simultaneously for comparison.
 */
@Component
public class BidParityLiveAutoScanMonitor {

    private static final Logger log = LoggerFactory.getLogger(BidParityLiveAutoScanMonitor.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OptionArbAutoExecService autoExecService;
    private final BidParityService bidParityService;
    private final OptionArbHistoryService historyService;

    public BidParityLiveAutoScanMonitor(OptionArbAutoExecService autoExecService,
                                        BidParityService bidParityService,
                                        OptionArbHistoryService historyService) {
        this.autoExecService = autoExecService;
        this.bidParityService = bidParityService;
        this.historyService = historyService;
    }

    @Scheduled(fixedDelayString = "${option-arb.live-scan-interval:30000}", initialDelay = 20000)
    public void tick() {
        Map<String, Object> settings = autoExecService.getSettings();
        boolean liveOn = Boolean.TRUE.equals(settings.get("enabled"));
        boolean paperOn = autoExecService.isPaperAutoEnabled();
        if (!liveOn && !paperOn) return;

        String broker = String.valueOf(settings.getOrDefault("broker", "NAVIA")).toUpperCase();
        boolean runLive = liveOn && !"PAPER".equals(broker);

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 16)) || now.isAfter(LocalTime.of(15, 25))) return;

        List<String> underlyings = new ArrayList<>();
        if (Boolean.TRUE.equals(settings.get("niftyEnabled"))) underlyings.add("NIFTY");
        if (Boolean.TRUE.equals(settings.get("bankniftyEnabled"))) underlyings.add("BANKNIFTY");
        if (underlyings.isEmpty()) {
            // Paper defaults: still scan NIFTY+BN when indices not explicitly enabled
            if (paperOn) {
                underlyings.add("NIFTY");
                underlyings.add("BANKNIFTY");
            } else {
                return;
            }
        }

        try {
            for (String u : underlyings) {
                bidParityService.scanBidParity(u, "BOTH");
            }
            LocalDateTime since = LocalDateTime.now().minusMinutes(3);
            List<OptionArbOpportunity> recent = historyService.getRepository()
                    .findByScanTimeBetween(since, LocalDateTime.now());
            List<OptionArbOpportunity> candidates = recent.stream()
                    .filter(o -> o.getUnderlying() != null)
                    .filter(o -> "NIFTY".equalsIgnoreCase(o.getUnderlying())
                            || "BANKNIFTY".equalsIgnoreCase(o.getUnderlying()))
                    .filter(o -> {
                        String st = o.getStrategyType() != null ? o.getStrategyType().toUpperCase() : "";
                        return st.contains("PARITY") || st.contains("BID");
                    })
                    .toList();
            if (candidates.isEmpty()) return;

            if (paperOn) {
                autoExecService.evaluateAndExecutePaper(candidates);
            }
            if (runLive) {
                autoExecService.evaluateAndExecute(candidates);
            }
        } catch (Exception e) {
            log.warn("Bid Parity dual-track scan failed: {}", e.getMessage());
            autoExecService.addLog("SCAN", "ERROR", e.getMessage());
        }
    }
}
