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
import java.util.*;

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
        log.info("Chartink preopen webhook: scan={} stocks={}", request.getScanName(), request.getStocks());
        List<Map<String, Object>> results = processBatch(request, true);
        return ResponseEntity.ok(Map.of("success", true, "results", results));
    }

    @PostMapping("/intraday")
    public ResponseEntity<Map<String, Object>> receiveIntraday(@RequestBody ChartinkWebhookRequest request) {
        log.info("Chartink intraday webhook: scan={} stocks={}", request.getScanName(), request.getStocks());
        List<Map<String, Object>> results = processBatch(request, false);
        return ResponseEntity.ok(Map.of("success", true, "results", results));
    }

    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> receiveExit(@RequestBody ChartinkWebhookRequest request) {
        log.info("Chartink exit webhook: scan={} stocks={}", request.getScanName(), request.getStocks());
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

    /**
     * Receives generic Chartink scanner data and evaluates ALL enabled strategies.
     * This eliminates the need for per-strategy Chartink scanners — one webhook feeds all strategies.
     */
    @PostMapping("/scanner-data")
    public ResponseEntity<Map<String, Object>> receiveScannerData(@RequestBody ChartinkWebhookRequest request) {
        log.info("Chartink universal scanner data: stocks={}", request.getStocks());
        List<Map<String, Object>> results = new ArrayList<>();
        List<StockHit> hits = request.parseHits();

        if (hits.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "results", results));
        }

        for (StockHit hit : hits) {
            Map<String, Object> result = processSingleStockAllStrategies(hit, request.getScanName());
            results.add(result);
        }

        return ResponseEntity.ok(Map.of("success", true, "results", results));
    }

    private static final Map<String, String> FIELD_MAP = new HashMap<>();
    static {
        FIELD_MAP.put("vwap", "vwap");
        FIELD_MAP.put("rsi14", "rsi14");
        FIELD_MAP.put("rsi", "rsi14");
        FIELD_MAP.put("atr14", "atr14");
        FIELD_MAP.put("atr", "atr14");
        FIELD_MAP.put("adx14", "adx14");
        FIELD_MAP.put("volume", "volume");
        FIELD_MAP.put("buyerqty", "buyerQty");
        FIELD_MAP.put("buyer qty", "buyerQty");
        FIELD_MAP.put("sellerqty", "sellerQty");
        FIELD_MAP.put("seller qty", "sellerQty");
        FIELD_MAP.put("bidqty", "bidQty");
        FIELD_MAP.put("bid qty", "bidQty");
        FIELD_MAP.put("askqty", "askQty");
        FIELD_MAP.put("ask qty", "askQty");
        FIELD_MAP.put("change", "changePct");
        FIELD_MAP.put("change%", "changePct");
        FIELD_MAP.put("change_pct", "changePct");
        FIELD_MAP.put("gap", "gapPct");
        FIELD_MAP.put("gap%", "gapPct");
        FIELD_MAP.put("gap_pct", "gapPct");
        FIELD_MAP.put("prevclose", "prevClose");
        FIELD_MAP.put("prev close", "prevClose");
        FIELD_MAP.put("open", "open");
        FIELD_MAP.put("high", "high");
        FIELD_MAP.put("low", "low");
        FIELD_MAP.put("vix", "vix");
        FIELD_MAP.put("unfilledratio", "unfilledRatio");
        FIELD_MAP.put("unfilled ratio", "unfilledRatio");
        FIELD_MAP.put("vwapdeviation", "vwapDeviationPct");
        FIELD_MAP.put("vwap deviation", "vwapDeviationPct");
        FIELD_MAP.put("vwap_deviation", "vwapDeviationPct");
        FIELD_MAP.put("rvol", "rvol");
        FIELD_MAP.put("bestbid", "bestBid");
        FIELD_MAP.put("best ask", "bestAsk");
        FIELD_MAP.put("bestask", "bestAsk");
        FIELD_MAP.put("niftychange", "niftyChangePct");
        FIELD_MAP.put("nifty change", "niftyChangePct");
        FIELD_MAP.put("stockcategory", "stockCategory");
        FIELD_MAP.put("stock category", "stockCategory");
    }

    private static String normalizeFieldName(String raw) {
        String normalized = raw.toLowerCase()
                .replaceAll("[()%]", "")
                .replaceAll("\\s+", " ")
                .trim();
        String mapped = FIELD_MAP.get(normalized);
        if (mapped != null) return mapped;
        String compact = normalized.replaceAll("\\s+", "");
        mapped = FIELD_MAP.get(compact);
        return mapped != null ? mapped : compact;
    }

    private static BigDecimal extractDecimal(Map<String, String> indicators, String rawField) {
        String key = normalizeFieldName(rawField);
        for (Map.Entry<String, String> entry : indicators.entrySet()) {
            if (normalizeFieldName(entry.getKey()).equals(key)) {
                try { return new BigDecimal(entry.getValue().trim()); } catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }

    private static Long extractLong(Map<String, String> indicators, String rawField) {
        String key = normalizeFieldName(rawField);
        for (Map.Entry<String, String> entry : indicators.entrySet()) {
            if (normalizeFieldName(entry.getKey()).equals(key)) {
                try { return Long.parseLong(entry.getValue().trim()); } catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }

    private List<Map<String, Object>> processBatch(ChartinkWebhookRequest request, boolean isPreOpen) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<StockHit> hits = request.parseHits();
        String scannerName = request.getScanName();

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
            Map<String, String> ind = hit.indicators();
            ChartinkPayload payload = new ChartinkPayload(
                    scannerName,                  // scannerName
                    scannerName,                  // scanName (fallback to scannerName)
                    hit.symbol(),                 // symbol
                    "NSE",                        // exchange
                    hit.triggerPrice(),           // ltp
                    extractLong(ind, "volume"),       // volume
                    extractLong(ind, "buyerQty"),     // buyerQty
                    extractLong(ind, "sellerQty"),    // sellerQty
                    extractDecimal(ind, "changePct"), // changePct
                    extractDecimal(ind, "gapPct"),    // gapPct
                    extractDecimal(ind, "vwapDeviationPct"), // vwapDeviationPct
                    extractDecimal(ind, "atr14"),         // atr14
                    extractDecimal(ind, "adx14"),         // adx14
                    extractDecimal(ind, "rvol"),          // rvol
                    extractDecimal(ind, "vwap"),          // vwap
                    extractDecimal(ind, "rsi14"),         // rsi14
                    extractDecimal(ind, "unfilledRatio"), // unfilledRatio
                    extractDecimal(ind, "vix"),           // vix
                    extractDecimal(ind, "open"),          // open
                    extractDecimal(ind, "high"),          // high
                    extractDecimal(ind, "low"),           // low
                    hit.triggerPrice(),                   // close
                    extractDecimal(ind, "prevClose"),     // prevClose
                    extractDecimal(ind, "bestBid"),       // bestBid
                    extractDecimal(ind, "bestAsk"),       // bestAsk
                    extractLong(ind, "bidQty"),           // bidQty
                    extractLong(ind, "askQty"),           // askQty
                    null,                        // niftyChangePct (String, from Chartink as Decimal)
                    null,                        // stockCategory
                    Instant.now(),               // timestamp
                    "CHARTINK_WEBHOOK"           // triggerType
            );

            log.info("Chartink payload: sym={} ltp={} vwap={} rsi={} atr={} buyer={} seller={} vol={}",
                    hit.symbol(), hit.triggerPrice(),
                    extractDecimal(ind, "vwap"), extractDecimal(ind, "rsi14"),
                    extractDecimal(ind, "atr14"), extractLong(ind, "buyerQty"),
                    extractLong(ind, "sellerQty"), extractLong(ind, "volume"));

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
                        "scannerName", scannerName != null ? scannerName : ""
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
            if (strategySignal.reason() != null) {
                entity.setReason(strategySignal.reason());
            }
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
            return Map.of("success", false, "reason", "ERROR", "symbol", hit.symbol(), "message", String.valueOf(e));
        }
    }

    /**
     * Process a single stock hit against ALL enabled strategies.
     * Each strategy that confirms generates its own independent signal.
     */
    private Map<String, Object> processSingleStockAllStrategies(StockHit hit, String scanName) {
        try {
            Map<String, String> ind = hit.indicators();
            String scannerName = scanName != null ? scanName : "UNIVERSAL_SCANNER";
            ChartinkPayload payload = new ChartinkPayload(
                    scannerName,
                    scannerName,
                    hit.symbol(),
                    "NSE",
                    hit.triggerPrice(),
                    extractLong(ind, "volume"),
                    extractLong(ind, "buyerQty"),
                    extractLong(ind, "sellerQty"),
                    extractDecimal(ind, "changePct"),
                    extractDecimal(ind, "gapPct"),
                    extractDecimal(ind, "vwapDeviationPct"),
                    extractDecimal(ind, "atr14"),
                    extractDecimal(ind, "adx14"),
                    extractDecimal(ind, "rvol"),
                    extractDecimal(ind, "vwap"),
                    extractDecimal(ind, "rsi14"),
                    extractDecimal(ind, "unfilledRatio"),
                    extractDecimal(ind, "vix"),
                    extractDecimal(ind, "open"),
                    extractDecimal(ind, "high"),
                    extractDecimal(ind, "low"),
                    hit.triggerPrice(),
                    extractDecimal(ind, "prevClose"),
                    extractDecimal(ind, "bestBid"),
                    extractDecimal(ind, "bestAsk"),
                    extractLong(ind, "bidQty"),
                    extractLong(ind, "askQty"),
                    null,
                    null,
                    Instant.now(),
                    "CHARTINK_WEBHOOK"
            );

            log.info("Universal scanner: sym={} ltp={} vwap={} rsi={} atr={} buyer={} seller={} vol={}",
                    hit.symbol(), hit.triggerPrice(),
                    extractDecimal(ind, "vwap"), extractDecimal(ind, "rsi14"),
                    extractDecimal(ind, "atr14"), extractLong(ind, "buyerQty"),
                    extractLong(ind, "sellerQty"), extractLong(ind, "volume"));

            String side = payload.inferSide();
            Map<Long, Signal> confirmed = strategyEvaluator.evaluateAll(payload);

            if (confirmed.isEmpty()) {
                return Map.of(
                        "success", false,
                        "reason", "NO_STRATEGY_CONFIRMED",
                        "symbol", hit.symbol()
                );
            }

            List<Map<String, Object>> signalResults = new ArrayList<>();

            for (Map.Entry<Long, Signal> entry : confirmed.entrySet()) {
                Long strategyId = entry.getKey();
                Signal strategySignal = entry.getValue();

                // Per-strategy cooldown
                if (!cooldownService.isAllowed(strategyId, hit.symbol(), strategySignal.side().name())) {
                    log.debug("Cooldown active for strategy {} {} {}", strategyId, hit.symbol(), strategySignal.side());
                    continue;
                }
                cooldownService.record(strategyId, hit.symbol(), strategySignal.side().name());

                SignalEntity entity = signalMapper.toSignalEntity(payload, null, null, strategyId);
                entity.setUserId(1L);
                entity.setConfidence(BigDecimal.valueOf(strategySignal.confidence()));
                entity.setStopLoss(strategySignal.stopLoss());
                entity.setTarget(strategySignal.target());
                if (strategySignal.reason() != null) {
                    entity.setReason(strategySignal.reason());
                }
                entity = signalRepository.save(entity);

                executionService.execute(entity);

                signalResults.add(Map.of(
                        "signalId", entity.getId(),
                        "strategyId", strategyId,
                        "strategy", strategySignal.reason(),
                        "side", strategySignal.side().name(),
                        "success", true
                ));
            }

            return Map.of(
                    "success", !signalResults.isEmpty(),
                    "symbol", hit.symbol(),
                    "side", side,
                    "signals", signalResults
            );

        } catch (Exception e) {
            log.error("Error processing stock {} for universal scanner", hit.symbol(), e);
            return Map.of("success", false, "reason", "ERROR", "symbol", hit.symbol(), "message", String.valueOf(e));
        }
    }
}
