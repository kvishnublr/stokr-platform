package com.stokr.chartink;

import com.stokr.chartink.ChartinkWebhookRequest.StockHit;
import com.stokr.engine.SignalEntity;
import com.stokr.engine.SignalRepository;
import com.stokr.strategy.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Receives webhooks from Chartink FREE/PRO (batch format with comma-separated stocks).
 * Three endpoints: preopen, intraday scanner hits, exit triggers.
 * Each stock in the batch is evaluated individually by the strategy engine.
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

    @PostMapping("/preopen")
    public ResponseEntity<Map<String, Object>> receivePreOpen(@RequestBody ChartinkWebhookRequest request) {
        log.info("Chartink preopen webhook: scan={} stocks={}", request.scanName(), request.stocks());
        List<Map<String, Object>> results = processBatch(request, true);
        return ResponseEntity.ok(Map.of("success", true, "results", results));
    }

    @PostMapping("/intraday")
    public ResponseEntity<Map<String, Object>> receiveIntraday(@RequestBody ChartinkWebhookRequest request) {
        log.info("Chartink intraday webhook: scan={} stocks={}", request.scanName(), request.stocks());
        List<Map<String, Object>> results = processBatch(request, false);
        return ResponseEntity.ok(Map.of("success", true, "results", results));
    }

    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> receiveExit(@RequestBody ChartinkWebhookRequest request) {
        log.info("Chartink exit webhook: scan={} stocks={}", request.scanName(), request.stocks());
        List<Map<String, Object>> results = new ArrayList<>();
        for (StockHit hit : request.parseHits()) {
            executionService.closePosition(hit.symbol(), "CHARTINK_EXIT_SCANNER");
            results.add(Map.of(
                    "symbol", hit.symbol(),
                    "action", "EXIT_EXECUTED",
                    "success", true
            ));
        }
        return ResponseEntity.ok(Map.of("success", true, "results", results));
    }

    private List<Map<String, Object>> processBatch(ChartinkWebhookRequest request, boolean isPreOpen) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<StockHit> hits = request.parseHits();
        String scannerName = request.scanName();

        if (hits.isEmpty()) {
            log.warn("No stocks parsed from webhook payload");
            return List.of(Map.of("success", false, "reason", "NO_STOCKS"));
        }

        for (StockHit hit : hits) {
            Map<String, Object> result = processSingleStock(scannerName, hit, isPreOpen);
            results.add(result);
        }
        return results;
    }

    private Map<String, Object> processSingleStock(String scannerName, StockHit hit, boolean isPreOpen) {
        try {
            ChartinkPayload payload = new ChartinkPayload(
                    scannerName,         // scannerName
                    scannerName,         // scanName (fallback to scannerName)
                    hit.symbol(),        // symbol
                    "NSE",               // exchange
                    hit.triggerPrice(),  // ltp
                    null,                // volume
                    null,                // buyerQty
                    null,                // sellerQty
                    null,                // changePct
                    null,                // gapPct
                    null,                // vwapDeviationPct
                    null,                // atr14
                    null,                // adx14
                    null,                // rvol
                    null,                // vwap
                    null,                // rsi14
                    null,                // unfilledRatio
                    null,                // vix
                    null,                // open
                    null,                // high
                    null,                // low
                    hit.triggerPrice(),  // close
                    null,                // prevClose
                    null,                // bestBid
                    null,                // bestAsk
                    null,                // bidQty
                    null,                // askQty
                    null,                // niftyChangePct
                    null,                // stockCategory
                    Instant.now(),       // timestamp
                    "CHARTINK_WEBHOOK"   // triggerType
            );

            // 1. Cooldown check
            String side = payload.inferSide();
            if (!cooldownService.isAllowed(scannerName, hit.symbol(), side)) {
                return Map.of("success", false, "reason", "COOLDOWN", "symbol", hit.symbol());
            }
            cooldownService.record(scannerName, hit.symbol(), side);

            // 2. Strategy evaluation
            Signal strategySignal = strategyEvaluator.evaluate(payload);
            if (strategySignal == null || !strategySignal.isValid()) {
                log.debug("Strategy did not confirm: {} {} scanner={}", hit.symbol(), side, scannerName);
                return Map.of(
                        "success", false,
                        "reason", "STRATEGY_NOT_CONFIRMED",
                        "symbol", hit.symbol(),
                        "scannerName", scannerName
                );
            }

            log.info("Strategy CONFIRMED: {} {} | reason={} confidence={}",
                    hit.symbol(), strategySignal.side(), strategySignal.reason(),
                    strategySignal.confidence());

            // 3. Map to SignalEntity
            Long strategyId = strategyRouter.resolveStrategyId(scannerName);
            SignalEntity entity = signalMapper.toSignalEntity(payload, null, null, strategyId);
            entity.setUserId(1L);
            entity.setConfidence(BigDecimal.valueOf(strategySignal.confidence()));
            entity.setStopLoss(strategySignal.stopLoss());
            entity.setTarget(strategySignal.target());
            entity.setReason(strategySignal.reason());
            entity = signalRepository.save(entity);

            // 4. Execute
            executionService.execute(entity);

            return Map.of(
                    "success", true,
                    "signalId", entity.getId(),
                    "symbol", hit.symbol(),
                    "action", isPreOpen ? "QUEUED_FOR_OPEN" : "EXECUTED",
                    "strategy", strategySignal.reason()
            );

        } catch (Exception e) {
            log.error("Error processing stock {} for scanner {}", hit.symbol(), scannerName, e);
            return Map.of("success", false, "reason", "ERROR", "symbol", hit.symbol(), "message", e.getMessage());
        }
    }
}
