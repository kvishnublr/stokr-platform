package com.stokr.engine;

import com.stokr.marketdata.MarketDataService;
import com.stokr.marketdata.ZerodhaLiveDataScheduler;
import com.stokr.oms.Position;
import com.stokr.oms.PositionService;
import com.stokr.risk.KillSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEngine {

    private final DeploymentService          deploymentService;
    private final SignalProcessor            signalProcessor;
    private final MarketDataService          marketDataService;
    private final ZerodhaLiveDataScheduler   liveDataScheduler;
    private final KillSwitchService          killSwitchService;
    private final PositionService            positionService;
    private final ExitManager               exitManager;
    private final SignalRepository           signalRepository;

    private static final LocalTime EOD_SQUAREOFF = LocalTime.of(15, 15);
    private static final ZoneId    IST           = ZoneId.of("Asia/Kolkata");

    // Trailing SL: best price seen per position since entry — reset on square-off
    private final Map<Long, BigDecimal> bestPriceMap = new ConcurrentHashMap<>();

    public void runScanCycle() {
        if (!marketDataService.isMarketOpen()) {
            log.debug("Market closed, skipping scan cycle");
            return;
        }

        if (killSwitchService.isActive()) {
            log.warn("Kill switch active — scan cycle blocked");
            return;
        }

        List<Deployment> activeDeployments = deploymentService.getAllActiveDeployments();
        if (activeDeployments.isEmpty()) {
            log.debug("No active deployments");
            return;
        }

        log.info("Scan cycle: {} active deployments", activeDeployments.size());

        boolean isEod = LocalTime.now(IST).isAfter(EOD_SQUAREOFF);

        for (Deployment deployment : activeDeployments) {
            try {
                if (isEod) {
                    squareOffAll(deployment);
                } else {
                    processExits(deployment);
                    signalProcessor.processDeployment(deployment);
                }
            } catch (Exception e) {
                log.error("Scan cycle error for deployment {}", deployment.getId(), e);
            }
        }
    }

    /**
     * Check every open position against its SL and target.
     * Uses per-signal trailTriggerPct / trailDistancePct instead of hardcoded values.
     */
    private void processExits(Deployment deployment) {
        List<Position> open = positionService.getOpenPositions(deployment.getId());
        if (open.isEmpty()) return;

        for (Position pos : open) {
            try {
                BigDecimal ltp = marketDataService.getLtp(pos.getSymbol());
                if (ltp.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("LTP unavailable for {} — skipping exit check", pos.getSymbol());
                    continue;
                }

                SignalEntity signal = signalRepository
                    .findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                        deployment.getId(), pos.getSymbol(), "EXECUTED")
                    .orElse(null);

                if (signal == null) {
                    log.debug("No EXECUTED signal found for {}/{} — skipping exit",
                        deployment.getId(), pos.getSymbol());
                    continue;
                }

                BigDecimal entry  = signal.getEntryPrice();
                BigDecimal sl     = signal.getStopLoss();
                BigDecimal target = signal.getTarget();

                if (entry == null || sl == null || target == null) continue;

                // Per-signal trailing stop params
                double trailTrigger  = signal.getTrailTriggerPct()  != null ? signal.getTrailTriggerPct()  : 0.5;
                double trailDistance = signal.getTrailDistancePct() != null ? signal.getTrailDistancePct() : 0.3;
                double trailFactor   = 1.0 - trailDistance / 100.0;

                // Update trailing best price
                BigDecimal best = bestPriceMap.merge(pos.getId(), ltp, BigDecimal::max);

                BigDecimal gainPct = ltp.subtract(entry)
                    .divide(entry, 6, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

                if (gainPct.compareTo(BigDecimal.valueOf(trailTrigger)) >= 0) {
                    BigDecimal trailSl = best.multiply(BigDecimal.valueOf(trailFactor));
                    if (trailSl.compareTo(sl) > 0) sl = trailSl;
                }

                if (ltp.compareTo(sl) <= 0) {
                    log.info("SL hit: {} ltp={} sl={}", pos.getSymbol(), ltp, sl);
                    updateSignalStatus(signal, "SL_HIT");
                    squareOff(deployment, pos, ltp);
                } else if (ltp.compareTo(target) >= 0) {
                    log.info("TARGET hit: {} ltp={} target={}", pos.getSymbol(), ltp, target);
                    updateSignalStatus(signal, "TARGET_HIT");
                    squareOff(deployment, pos, ltp);
                }

            } catch (Exception e) {
                log.error("Exit check error: deployment={} symbol={}", deployment.getId(), pos.getSymbol(), e);
            }
        }
    }

    private void squareOffAll(Deployment deployment) {
        List<Position> open = positionService.getOpenPositions(deployment.getId());
        if (open.isEmpty()) return;
        log.info("EOD square-off: deployment {} has {} open positions", deployment.getId(), open.size());
        for (Position pos : open) {
            BigDecimal ltp = marketDataService.getLtp(pos.getSymbol());
            if (ltp.compareTo(BigDecimal.ZERO) <= 0) ltp = pos.getAvgPrice();
            // Mark signal as EOD_EXIT before squaring off
            signalRepository.findFirstByDeploymentIdAndSymbolAndStatusOrderByCreatedAtDesc(
                    deployment.getId(), pos.getSymbol(), "EXECUTED")
                .ifPresent(s -> updateSignalStatus(s, "EOD_EXIT"));
            squareOff(deployment, pos, ltp);
        }
    }

    private void squareOff(Deployment deployment, Position pos, BigDecimal exitPrice) {
        exitManager.squareOffPosition(deployment, pos, exitPrice);
        bestPriceMap.remove(pos.getId());
    }

    private void updateSignalStatus(SignalEntity signal, String exitType) {
        try {
            signal.setStatus(exitType);
            signal.setExitType(exitType);
            signal.setExitTime(Instant.now());
            signalRepository.save(signal);
        } catch (Exception e) {
            log.warn("Failed to update signal {} status to {}: {}", signal.getId(), exitType, e.getMessage());
        }
    }
}
