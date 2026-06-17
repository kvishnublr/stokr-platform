package com.stokr.chartink;

import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import com.stokr.filter.MovementAssuranceFilter;
import com.stokr.filter.MovementAssuranceFilter.MovementResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives webhooks from Chartink Premium.
 * Three endpoints: preopen, intraday scanner hits, exit triggers.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/chartink")
@RequiredArgsConstructor
public class ChartinkWebhookController {

    private final SignalCooldownService cooldownService;
    private final ChartinkSignalMapper signalMapper;
    private final MovementAssuranceFilter movementAssurance;
    private final StrategyRouter strategyRouter;
    private final SignalRepository signalRepository;
    private final ChartinkExecutionService executionService;
    private final EnsembleService ensembleService;

    /**
     * 9:09 AM pre-market webhook.
     * Receives pre-open signals before market open.
     */
    @PostMapping("/preopen")
    public ResponseEntity<Map<String, Object>> receivePreOpen(@RequestBody ChartinkPayload payload) {
        log.info("Chartink preopen webhook: {} {} @ {}", payload.scannerName(), payload.symbol(), payload.ltp());
        return processSignal(payload, true);
    }

    /**
     * 1-minute scanner webhooks during market hours.
     * Receives hits from ORB, VWAP, Volume, Imbalance scanners.
     */
    @PostMapping("/intraday")
    public ResponseEntity<Map<String, Object>> receiveIntraday(@RequestBody ChartinkPayload payload) {
        log.info("Chartink intraday webhook: {} {} @ {}", payload.scannerName(), payload.symbol(), payload.ltp());
        return processSignal(payload, false);
    }

    /**
     * Exit condition scanner webhook.
     * Chartink sends when exit conditions are met.
     */
    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> receiveExit(@RequestBody ChartinkPayload payload) {
        log.info("Chartink exit webhook: {} {} @ {}", payload.scannerName(), payload.symbol(), payload.ltp());
        executionService.closePosition(payload.symbol(), "CHARTINK_EXIT_SCANNER");
        return ResponseEntity.ok(Map.of(
                "success", true,
                "action", "EXIT_EXECUTED",
                "symbol", payload.symbol()
        ));
    }

    private ResponseEntity<Map<String, Object>> processSignal(ChartinkPayload payload, boolean isPreOpen) {
        try {
            // 1. Validate scanner is known
            if (!strategyRouter.isKnownScanner(payload.scannerName())) {
                log.warn("Unknown scanner: {}", payload.scannerName());
                return ResponseEntity.ok(Map.of("success", false, "reason", "UNKNOWN_SCANNER"));
            }

            // 2. Cooldown check
            String side = payload.inferSide();
            if (!cooldownService.isAllowed(payload.symbol(), side)) {
                return ResponseEntity.ok(Map.of("success", false, "reason", "COOLDOWN"));
            }

            // 3. Movement Assurance Layer
            MovementResult ma = movementAssurance.evaluate(payload);
            log.info("MovementScore for {} {}: {} (pass={})",
                    payload.symbol(), payload.scannerName(), ma.score(), ma.pass());

            // 4. Map to SignalEntity
            Long strategyId = strategyRouter.resolveStrategyId(payload.scannerName());
            SignalEntity signal = signalMapper.toSignalEntity(payload, null, null, strategyId);
            signal.setMovementScore(ma.score());
            signal.setFailedFilters(String.join(",", ma.failedFilters()));

            // 5. Store signal regardless of pass/fail (for audit)
            SignalEntity saved = signalRepository.save(signal);
            cooldownService.record(payload.symbol(), side);

            // 6. If Movement Assurance passed, execute
            if (ma.pass()) {
                log.info("Signal PASSED Movement Assurance: {} {} score={}",
                        payload.symbol(), side, ma.score());

                // Ensemble check (skip if ensemble doesn't confirm)
                var ensemble = ensembleService.computeScore(payload.symbol(), saved.getCreatedAt());
                boolean ensembleConfirms = "LONG".equals(ensemble.decision()) && "BUY".equals(side)
                        || "SHORT".equals(ensemble.decision()) && "SELL".equals(side);

                if (ensemble.shouldTrade() && ensembleConfirms) {
                    executionService.execute(saved);
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "signalId", saved.getId(),
                            "movementScore", ma.score(),
                            "ensembleScore", ensemble.score(),
                            "action", isPreOpen ? "QUEUED_FOR_OPEN" : "EXECUTED",
                            "componentScores", ma.componentScores()
                    ));
                } else {
                    saved.setStatus("ENSEMBLE_FILTERED");
                    signalRepository.save(saved);
                    return ResponseEntity.ok(Map.of(
                            "success", false,
                            "signalId", saved.getId(),
                            "movementScore", ma.score(),
                            "ensembleScore", ensemble.score(),
                            "reason", "ENSEMBLE_NOT_CONFIRMED",
                            "componentScores", ma.componentScores()
                    ));
                }
            } else {
                log.info("Signal FAILED Movement Assurance: {} {} score={} failed={}",
                        payload.symbol(), side, ma.score(), ma.failedFilters());
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "signalId", saved.getId(),
                        "movementScore", ma.score(),
                        "reason", "MOVEMENT_ASSURANCE_FAILED",
                        "failedFilters", ma.failedFilters(),
                        "componentScores", ma.componentScores()
                ));
            }

        } catch (Exception e) {
            log.error("Error processing Chartink webhook", e);
            return ResponseEntity.ok(Map.of("success", false, "reason", "ERROR", "message", e.getMessage()));
        }
    }
}
