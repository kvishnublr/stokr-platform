package com.stokr.chartink;

import com.stokr.marketdata.Candle;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChartinkStrategyEvaluator {

    private final StrategyRouter strategyRouter;
    private final StrategyService strategyService;
    private final ChartinkTickBuffer tickBuffer;

    public Signal evaluate(ChartinkPayload payload) {
        String scannerName = payload.scannerName();
        if (scannerName == null) return null;

        String strategyType = strategyRouter.resolveStrategyName(scannerName);
        Long strategyId = strategyRouter.resolveStrategyId(scannerName);
        if (strategyId == null || strategyId == 0L) {
            log.debug("No strategy mapped for scanner: {}", scannerName);
            return null;
        }

        MarketContext context = buildContext(payload);
        return strategyService.evaluateSignal(strategyId, context);
    }

    /**
     * Evaluate a payload against ALL enabled strategies.
     * Returns a map of (strategyId -> Signal) for every strategy that confirmed.
     */
    public Map<Long, Signal> evaluateAll(ChartinkPayload payload) {
        Map<Long, Signal> results = new LinkedHashMap<>();
        if (payload == null) return results;

        List<Strategy> enabled = strategyService.getEnabledStrategies();
        if (enabled.isEmpty()) {
            log.debug("No enabled strategies to evaluate");
            return results;
        }

        MarketContext context = buildContext(payload);

        for (Strategy strategy : enabled) {
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

        return new MarketContext(
                payload.symbol(), candles, currentPrice, vwap, indicators, extras
        );
    }
}
