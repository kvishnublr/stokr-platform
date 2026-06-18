package com.stokr.chartink;

import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import com.stokr.strategy.Signal;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives webhooks from Chartink Premium.
 * Three endpoints: preopen, intraday scanner hits, exit triggers.
 * Each tick is evaluated by the corresponding backend strategy engine
 * before execution is considered.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/chartink")
@RequiredArgsConstructor
public class ChartinkWebhookController {

    private final SignalCooldownService cooldownService;
    private final ChartinkSignalMapper signalMapper;
    private final StrategyRouter strategyRouter;
    private final SignalRepository signalRepository;
    private final ChartinkExecutionService executionService;
    private final ChartinkStrategyEvaluator strategyEvaluator;

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
            // 1. Cooldown check (per scanner-symbol-side)
            String side = payload.inferSide();
            if (!cooldownService.isAllowed(payload.scannerName(), payload.symbol(), side)) {
                return ResponseEntity.ok(Map.of("success", false, "reason", "COOLDOWN"));
            }
            cooldownService.record(payload.scannerName(), payload.symbol(), side);

            // 2. Run strategy evaluation (primary decision maker)
            Signal strategySignal = strategyEvaluator.evaluate(payload);
            if (strategySignal == null || !strategySignal.isValid()) {
                log.info("Strategy did not confirm: {} {} scanner={}",
                        payload.symbol(), side, payload.scannerName());
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "reason", "STRATEGY_NOT_CONFIRMED",
                        "symbol", payload.symbol(),
                        "scannerName", payload.scannerName()
                ));
            }

            log.info("Strategy CONFIRMED: {} {} | reason={} confidence={}",
                    payload.symbol(), strategySignal.side(), strategySignal.reason(),
                    strategySignal.confidence());

            // 3. Map to SignalEntity and execute
            Long strategyId = strategyRouter.resolveStrategyId(payload.scannerName());
            SignalEntity entity = signalMapper.toSignalEntity(payload, null, null, strategyId);
            entity.setUserId(1L);
            entity.setConfidence(BigDecimal.valueOf(strategySignal.confidence()));
            entity.setStopLoss(strategySignal.stopLoss());
            entity.setTarget(strategySignal.target());
            entity.setReason(strategySignal.reason());
            entity = signalRepository.save(entity);

            // 4. Execute
            executionService.execute(entity);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "signalId", entity.getId(),
                    "action", isPreOpen ? "QUEUED_FOR_OPEN" : "EXECUTED",
                    "strategy", strategySignal.reason()
            ));

        } catch (Exception e) {
            log.error("Error processing Chartink webhook", e);
            return ResponseEntity.ok(Map.of("success", false, "reason", "ERROR", "message", e.getMessage()));
        }
    }
}
