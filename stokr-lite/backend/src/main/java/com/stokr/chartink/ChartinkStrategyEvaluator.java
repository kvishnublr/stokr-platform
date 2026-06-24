package com.stokr.chartink;

import com.stokr.marketdata.Candle;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChartinkStrategyEvaluator {

    private final StrategyRouter strategyRouter;
    private final StrategyService strategyService;
    private final ChartinkTickBuffer tickBuffer;
    private final StrategyUniverseMappingRepository mappingRepository;
    private final UniverseSymbolRepository symbolRepository;

    public Signal evaluate(ChartinkPayload payload) {
        String scannerName = payload.scannerName();
        if (scannerName == null) return null;

        String strategyType = strategyRouter.resolveStrategyName(scannerName);
        Long strategyId = strategyRouter.resolveStrategyId(scannerName);
        if (strategyId == null || strategyId == 0L) {
            log.debug("No strategy mapped for scanner: {}", scannerName);
            return null;
        }

        if (!isSymbolAllowedForStrategy(strategyId, payload.symbol())) {
            log.debug("Symbol {} not in any mapped universe for strategy {}", payload.symbol(),
                    strategyService.getStrategy(strategyId).getName());
            return null;
        }

        MarketContext context = buildContext(payload);
        return strategyService.evaluateSignal(strategyId, context);
    }

    /**
     * Evaluate a payload against ALL enabled strategies whose universe mappings
     * include the incoming symbol. Returns a map of (strategyId -> Signal)
     * for every strategy that confirmed.
     */
    public Map<Long, Signal> evaluateAll(ChartinkPayload payload) {
        Map<Long, Signal> results = new LinkedHashMap<>();
        if (payload == null) return results;

        List<Strategy> enabled = strategyService.getEnabledStrategies();
        if (enabled.isEmpty()) {
            log.debug("No enabled strategies to evaluate");
            return results;
        }

        // Pre-compute allowed symbols per strategy to avoid repeated DB queries
        Map<Long, Set<String>> allowedSymbolsByStrategy = computeAllowedSymbols(enabled);

        MarketContext context = buildContext(payload);

        for (Strategy strategy : enabled) {
            if (!isSymbolInAllowedSet(payload.symbol(), strategy.getId(), allowedSymbolsByStrategy)) {
                log.debug("Symbol {} not in mapped universe for strategy {}, skipping",
                        payload.symbol(), strategy.getName());
                continue;
            }
            try {
                Signal signal = strategyService.evaluateSignal(strategy.getId(), context);
                if (signal != null && signal.isValid()) {
                    results.put(strategy.getId(), signal);
                    log.info("Strategy {} CONFIRMED for {}: side={} confidence={}",
                            strategy.getName(), payload.symbol(), signal.side(), signal.confidence());
                }
            } catch (Exception e) {
                log.warn("Error evaluating strategy {} for {}: {}", strategy.getName(), payload.symbol(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * Check whether a given symbol belongs to at least one universe mapped to the strategy.
     * If the strategy has no mappings at all, allow all symbols (backward compat).
     */
    private boolean isSymbolAllowedForStrategy(Long strategyId, String symbol) {
        List<StrategyUniverseMapping> mappings = mappingRepository.findByStrategyId(strategyId);
        if (mappings.isEmpty()) return true;
        return mappings.stream()
                .filter(StrategyUniverseMapping::isRuntimeEnabled)
                .anyMatch(m -> {
                    List<String> symbols = symbolRepository.findByGroupIdAndEnabledTrue(m.getUniverseGroupId())
                            .stream().map(UniverseSymbol::getSymbol).toList();
                    return symbols.contains(symbol.toUpperCase());
                });
    }

    /**
     * Build a lookup map: strategyId -> Set<allowedSymbol> for all enabled strategies.
     * Strategies with no mappings get an empty set (meaning unrestricted).
     */
    private Map<Long, Set<String>> computeAllowedSymbols(List<Strategy> strategies) {
        // Load all runtime-enabled mappings in one batch
        List<StrategyUniverseMapping> allMappings = mappingRepository.findByRuntimeEnabledTrue();
        // Group by strategyId
        Map<Long, List<StrategyUniverseMapping>> byStrategy = allMappings.stream()
                .collect(Collectors.groupingBy(StrategyUniverseMapping::getStrategyId));

        Map<Long, Set<String>> result = new HashMap<>();
        for (Strategy s : strategies) {
            List<StrategyUniverseMapping> stratMappings = byStrategy.get(s.getId());
            if (stratMappings == null || stratMappings.isEmpty()) {
                result.put(s.getId(), Collections.emptySet()); // unrestricted
            } else {
                Set<String> symbols = new HashSet<>();
                for (StrategyUniverseMapping m : stratMappings) {
                    if (!m.isRuntimeEnabled()) continue;
                    List<String> groupSymbols = symbolRepository.findByGroupIdAndEnabledTrue(m.getUniverseGroupId())
                            .stream().map(UniverseSymbol::getSymbol)
                            .map(String::toUpperCase).toList();
                    symbols.addAll(groupSymbols);
                }
                result.put(s.getId(), symbols);
            }
        }
        return result;
    }

    private boolean isSymbolInAllowedSet(String symbol, Long strategyId, Map<Long, Set<String>> lookup) {
        Set<String> allowed = lookup.get(strategyId);
        if (allowed == null) return true;    // no mappings = unrestricted
        if (allowed.isEmpty()) return true;  // empty set = unrestricted
        return allowed.contains(symbol.toUpperCase());
    }

    private MarketContext buildContext(ChartinkPayload payload) {
        tickBuffer.add(payload);
        List<Candle> candles = tickBuffer.toCandles(payload.symbol());
        BigDecimal currentPrice = payload.ltp() != null ? payload.ltp() : BigDecimal.ZERO;
        BigDecimal vwap = payload.vwap();

        Map<String, BigDecimal> indicators = new HashMap<>();
        if (payload.rsi14() != null) indicators.put("RSI14", payload.rsi14());
        if (payload.adx14() != null) indicators.put("ADX14", payload.adx14());
        if (payload.atr14() != null) indicators.put("ATR14", payload.atr14());

        Map<String, Object> extras = new HashMap<>();
        extras.put("buyerQty", payload.buyerQty());
        extras.put("sellerQty", payload.sellerQty());
        extras.put("atr14", payload.atr14());
        extras.put("rsi14", payload.rsi14());
        extras.put("gapPct", payload.gapPct());
        extras.put("prevClose", payload.prevClose());
        extras.put("vwapDeviationPct", payload.vwapDeviationPct());
        extras.put("unfilledRatio", payload.unfilledRatio());
        extras.put("vix", payload.vix());
        extras.put("rvol", payload.rvol());
        extras.put("volume", payload.volume());
        extras.put("bestBid", payload.bestBid());
        extras.put("bestAsk", payload.bestAsk());

        // ORB levels from tick-buffer candles for ORB_V / MORNING_SURGE strategies
        if (candles.size() >= 15) {
            List<Candle> orbWindow = candles.subList(0, 15);
            BigDecimal orbHigh = orbWindow.stream().map(Candle::high)
                .max(BigDecimal::compareTo).orElse(null);
            BigDecimal orbLow = orbWindow.stream().map(Candle::low)
                .min(BigDecimal::compareTo).orElse(null);
            if (orbHigh != null && orbLow != null) {
                extras.put("orbHigh",   orbHigh);
                extras.put("orbLow",    orbLow);
                extras.put("orbRange",  orbHigh.subtract(orbLow));
                extras.put("dayOpen",   candles.get(0).open());
                extras.put("chartinkOk", Boolean.TRUE);
            }
        }

        // Time info for time-gated strategies (VWAP_TRIPLE, ORB_V, MORNING_SURGE)
        if (payload.timestamp() != null) {
            java.time.LocalTime t = payload.timestamp().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalTime();
            extras.put("istHour",   t.getHour());
            extras.put("istMinute", t.getMinute());
        }

        return new MarketContext(
                payload.symbol(), candles, currentPrice, vwap, indicators, extras
        );
    }
}
