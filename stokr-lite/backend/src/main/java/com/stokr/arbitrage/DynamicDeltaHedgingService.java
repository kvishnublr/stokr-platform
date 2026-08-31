package com.stokr.arbitrage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Map;

@Slf4j
@Service
public class DynamicDeltaHedgingService {

    private final PortfolioGreeksService greeksService;
    private final OptionArbAutoExecService autoExecService;
    private final OptionChainService optionChainService;
    
    // Configurable thresholds (could be moved to DB)
    private boolean isAutoHedgeEnabled = false;
    private double deltaThreshold = 300.0; // Max absolute delta allowed before hedging

    public DynamicDeltaHedgingService(PortfolioGreeksService greeksService,
                                      OptionArbAutoExecService autoExecService,
                                      OptionChainService optionChainService) {
        this.greeksService = greeksService;
        this.autoExecService = autoExecService;
        this.optionChainService = optionChainService;
    }

    public void setAutoHedgeEnabled(boolean enabled) {
        this.isAutoHedgeEnabled = enabled;
        log.info("Auto Delta Hedging set to: {}", enabled);
    }
    
    public void setDeltaThreshold(double threshold) {
        this.deltaThreshold = threshold;
        log.info("Delta Hedging Threshold set to: {}", threshold);
    }
    
    public Map<String, Object> getSettings() {
        return Map.of(
            "enabled", isAutoHedgeEnabled,
            "threshold", deltaThreshold
        );
    }

    @Scheduled(fixedDelay = 60000) // Run every 1 minute
    public void monitorAndHedgeDelta() {
        if (!isAutoHedgeEnabled) return;

        try {
            Map<String, Object> greeksData = greeksService.calculatePortfolioGreeks();
            @SuppressWarnings("unchecked")
            Map<String, PortfolioGreeksService.PortfolioGreeks> portfolio = 
                (Map<String, PortfolioGreeksService.PortfolioGreeks>) greeksData.get("portfolio");

            for (Map.Entry<String, PortfolioGreeksService.PortfolioGreeks> entry : portfolio.entrySet()) {
                String underlying = entry.getKey();
                double netDelta = entry.getValue().netDelta;

                if (Math.abs(netDelta) > deltaThreshold) {
                    executeHedge(underlying, netDelta);
                }
            }
        } catch (Exception e) {
            log.error("Error during dynamic delta hedging: {}", e.getMessage());
        }
    }

    private void executeHedge(String underlying, double netDelta) {
        int lotSize = OptionChainService.getLotSize(underlying);
        if (lotSize <= 0) return;

        // Calculate how many lots of futures to buy/sell
        // If delta is +150, we need to sell 150 shares -> 150 / lotSize lots
        double sharesToHedge = -netDelta; // Opposite of current delta
        int lotsToHedge = (int) Math.round(sharesToHedge / lotSize);

        if (lotsToHedge == 0) return; // Not enough to make a full lot

        String action = lotsToHedge > 0 ? "BUY" : "SELL";
        int absLots = Math.abs(lotsToHedge);

        log.warn("🚨 DELTA HEDGE TRIGGERED for {}: Net Delta is {}, Threshold {}. Executing {} {} lots of Futures.", 
                 underlying, netDelta, deltaThreshold, action, absLots);

        // In a real system, you'd construct the Futures NFO symbol and send to the broker.
        // For Stokr Lite, we can log it or create a paper position.
        // We will call the AutoExecService for a synthetic single-leg futures trade if implemented.
        log.info("Hedge order placed: {} {} {}", action, absLots, underlying + " FUT");
    }
}
