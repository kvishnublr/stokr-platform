package com.stokr.strategy.integrity;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.integrity.IntegrityRejectionReason;
import com.stokr.marketdata.integrity.LookbackWindow;
import com.stokr.marketdata.integrity.MarketDataIntegrityService;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.context.StrategyContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fail-closed integrity facade for catalog strategy generators.
 */
@Component
@RequiredArgsConstructor
public class StrategyGeneratorIntegrityGate {

    private final MarketDataIntegrityService integrityService;
    private final MarketDataQueryService marketDataQueryService;

    public boolean isStrategyScanAllowed(String strategyKey, Instant asOf) {
        StrategyIntegrityProfile profile = StrategyIntegrityProfile.forStrategy(strategyKey);
        if (!profile.requiresNiftyOpeningSession()) {
            return true;
        }
        Instant anchor = asOf != null ? asOf : Instant.now();
        if (integrityService.isNiftyOpeningSessionReady(anchor)) {
            return true;
        }
        integrityService.recordRejection(
                strategyKey,
                MarketDataIntegrityService.NIFTY_50_SYMBOL,
                IntegrityRejectionReason.NIFTY_OPENING_INCOMPLETE,
                null,
                null,
                java.time.LocalDate.ofInstant(anchor, java.time.ZoneId.of("Asia/Kolkata")));
        return false;
    }

    public boolean passPreEvaluate(String strategyKey, String symbol, Instant asOf) {
        StrategyIntegrityProfile profile = StrategyIntegrityProfile.forStrategy(strategyKey);
        return integrityService.passesPreEvaluate(
                strategyKey,
                symbol,
                asOf,
                profile.requiresNiftyOpeningSession(),
                profile.requiresObiTicks(),
                profile.checkEquityCandleFreshness(),
                profile.checkIndexCandleFreshness()
        );
    }

    public Optional<List<MarketdataCandle>> sessionBars(
            String strategyKey,
            String symbol,
            String timeframe,
            int fetchBars,
            int lookbackBars,
            LookbackWindow window,
            StrategyContext context) {
        List<MarketdataCandle> raw = loadRawBars(symbol, timeframe, fetchBars, context);
        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();
        return integrityService.validateSessionBarSeries(strategyKey, symbol, raw, lookbackBars, window, asOf);
    }

    public Optional<List<MarketdataCandle>> sessionBarsWithoutLookback(
            String strategyKey,
            String symbol,
            String timeframe,
            int fetchBars,
            StrategyContext context) {
        return sessionBars(strategyKey, symbol, timeframe, fetchBars, 0, LookbackWindow.FIVE_MINUTE, context);
    }

    public boolean validateGapFillSessionSlice(
            String strategyKey,
            String symbol,
            List<MarketdataCandle> bars,
            int todayStartIdx,
            Instant asOf) {
        return integrityService.validateGapFillSessionSlice(strategyKey, symbol, bars, todayStartIdx, asOf);
    }

    private List<MarketdataCandle> loadRawBars(String symbol, String timeframe, int maxBars, StrategyContext context) {
        if (context.asOf() != null) {
            return marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, maxBars, context.asOf());
        }
        return marketDataQueryService.lastBarsAsc(symbol, timeframe, maxBars);
    }
}
