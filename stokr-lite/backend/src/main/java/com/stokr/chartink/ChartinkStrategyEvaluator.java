package com.stokr.chartink;

import com.stokr.marketdata.Candle;
import com.stokr.strategy.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        MarketContext context = new MarketContext(
                payload.symbol(), candles, currentPrice, vwap, indicators, extras
        );

        return strategyService.evaluateSignal(strategyId, context);
    }
}
