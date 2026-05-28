package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.CommoditiesE2eTestTriggerService;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Minimal MCX probe: fires when admin queued a one-shot trigger or when scan-fire mode is enabled
 * and a fresh 1m candle exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
        strategyKey = "COMMODITIES_E2E_TEST",
        assetClass = "COMMODITY",
        segment = "MCX",
        exchange = "MCX",
        timeframe = "1m"
)
public class CommoditiesE2eTestSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "1m";

    private final MarketDataQueryService marketDataQueryService;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final CommoditiesE2eTestTriggerService triggerService;

    @Value("${stokr.commodities-e2e-test.enabled:true}")
    private boolean enabled;

    @Value("${stokr.commodities-e2e-test.scan-fire:false}")
    private boolean scanFire;

    @Value("${stokr.commodities-e2e-test.max-candle-age-seconds:180}")
    private long maxCandleAgeSeconds;

    @Override
    public String key() {
        return "COMMODITIES_E2E_TEST";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        if (!enabled) {
            return hold(context);
        }

        String symbol = context.symbol();
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();

        if (triggerService.consumePendingFire(symbol)) {
            log.info("commodities_e2e_test.manual_fire symbol={}", symbol);
            return bullishSignal(context, "COMMODITIES_E2E_TEST admin trigger");
        }

        if (!scanFire) {
            return hold(context);
        }

        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        List<MarketdataCandle> candles = marketDataQueryService.lastBarsAsc(symbol, TIMEFRAME, 1);
        if (candles == null || candles.isEmpty()) {
            return hold(context);
        }
        MarketdataCandle latest = candles.get(candles.size() - 1);
        if (latest.getOpenTime() == null) {
            return hold(context);
        }
        long ageSec = Duration.between(latest.getOpenTime(), asOf).getSeconds();
        if (ageSec > maxCandleAgeSeconds) {
            return hold(context);
        }

        log.info("commodities_e2e_test.scan_fire symbol={} candleAgeSec={}", symbol, ageSec);
        return bullishSignal(context, "COMMODITIES_E2E_TEST scan probe — fresh candle");
    }
}
