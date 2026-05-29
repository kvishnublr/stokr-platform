package com.stokr.marketdata.simulation;

import com.stokr.common.simulation.SimulationScenario;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.stokr.common.simulation.SimulationModeService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeds synthetic ticks and 1m candles through the same ingest/upsert path as live feeds.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulatedMarketDataEngine {

    private static final String NIFTY = "NIFTY 50";
    private static final String TIMEFRAME = "1m";

    private final MarketdataCandleRepository candleRepository;
    private final MarketDataService marketDataService;
    private final SimulationModeService simulationModeService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    public SimulatedMarketSession seedSession(
            SimulationScenario scenario,
            String symbol,
            BigDecimal basePrice,
            int sessionBars
    ) {
        if (!simulationModeService.isActive()) {
            throw new IllegalStateException("Simulation runtime is not enabled");
        }
        BigDecimal base = basePrice != null ? basePrice : BigDecimal.valueOf(100);
        Instant anchor = ZonedDateTime.now(zone).toInstant();
        List<SimulatedBar> equityBars = generateEquityPath(scenario, symbol, base, sessionBars, anchor);
        persistBars(symbol, equityBars);
        List<SimulatedBar> niftyBars = generateIndexPath(sessionBars, anchor);
        persistBars(NIFTY, niftyBars);
        replayTicks(symbol, equityBars);
        replayTicks(NIFTY, niftyBars);
        marketDataService.flushDirtyCandles();
        log.info("sim.market.seeded scenario={} symbol={} bars={} niftyBars={}",
                scenario, symbol, equityBars.size(), niftyBars.size());
        return new SimulatedMarketSession(symbol, base, equityBars, niftyBars, anchor);
    }

    public void pushLiveTicks(SimulatedMarketSession session, int tickCount, BigDecimal priceOverride) {
        if (session == null || session.equityBars().isEmpty()) {
            return;
        }
        SimulatedBar last = session.equityBars().get(session.equityBars().size() - 1);
        BigDecimal price = priceOverride != null ? priceOverride : last.close();
        Instant t = Instant.now();
        for (int i = 0; i < tickCount; i++) {
            MarketdataTick tick = new MarketdataTick();
            tick.setSymbol(session.symbol());
            tick.setTickTime(t.plusMillis(i * 200L));
            tick.setPrice(price);
            tick.setQuantity(BigDecimal.valueOf(1000 + i * 10));
            tick.setSource("SIMULATION");
            marketDataService.ingestTick(tick, zone);
        }
        marketDataService.flushDirtyCandles();
    }

    private void replayTicks(String symbol, List<SimulatedBar> bars) {
        for (SimulatedBar bar : bars) {
            for (int i = 0; i < 4; i++) {
                MarketdataTick tick = new MarketdataTick();
                tick.setSymbol(symbol);
                tick.setTickTime(bar.openTime().plusSeconds(15L * i));
                BigDecimal mid = bar.open().add(bar.close()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                tick.setPrice(mid);
                tick.setQuantity(bar.volume().divide(BigDecimal.valueOf(4), 0, RoundingMode.HALF_UP));
                marketDataService.ingestTick(tick, zone);
            }
        }
    }

    private void persistBars(String symbol, List<SimulatedBar> bars) {
        for (SimulatedBar bar : bars) {
            candleRepository.upsertCandle(
                    symbol,
                    TIMEFRAME,
                    bar.openTime(),
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    bar.volume()
            );
        }
    }

    private List<SimulatedBar> generateEquityPath(
            SimulationScenario scenario,
            String symbol,
            BigDecimal base,
            int bars,
            Instant endAnchor
    ) {
        Random rnd = new Random(symbol.hashCode() ^ scenario.name().hashCode());
        List<SimulatedBar> out = new ArrayList<>();
        BigDecimal price = base;
        double drift = driftForScenario(scenario);
        for (int i = bars - 1; i >= 0; i--) {
            Instant openTime = endAnchor.minus(i, ChronoUnit.MINUTES);
            double shock = (rnd.nextDouble() - 0.5) * volatilityForScenario(scenario);
            BigDecimal open = price;
            BigDecimal close = price.multiply(BigDecimal.valueOf(1 + drift + shock))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.001)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.999)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal volume = BigDecimal.valueOf(50_000 + rnd.nextInt(80_000));
            out.add(new SimulatedBar(openTime, open, high, low, close, volume));
            price = close;
        }
        return out;
    }

    private List<SimulatedBar> generateIndexPath(int bars, Instant endAnchor) {
        Random rnd = new Random(42);
        List<SimulatedBar> out = new ArrayList<>();
        BigDecimal price = BigDecimal.valueOf(24_500);
        for (int i = bars - 1; i >= 0; i--) {
            Instant openTime = endAnchor.minus(i, ChronoUnit.MINUTES);
            BigDecimal open = price;
            BigDecimal close = price.multiply(BigDecimal.valueOf(1 + (rnd.nextDouble() - 0.5) * 0.0008))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.0005)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.9995)).setScale(2, RoundingMode.HALF_UP);
            out.add(new SimulatedBar(openTime, open, high, low, close, BigDecimal.valueOf(100_000)));
            price = close;
        }
        return out;
    }

    private static double driftForScenario(SimulationScenario scenario) {
        return switch (scenario) {
            case GAP_FILL_WIN, VWAP_BOUNCE_WIN, NSE_SPIKE_WIN, TARGET_HIT -> 0.004;
            case GAP_FILL_LOSS, VWAP_BOUNCE_LOSS, SL_HIT -> -0.004;
            case PROTECTION_EXIT -> 0.001;
            case FEED_FAILURE -> 0.0;
            default -> 0.0005;
        };
    }

    private static double volatilityForScenario(SimulationScenario scenario) {
        return switch (scenario) {
            case NSE_SPIKE_WIN -> 0.012;
            case PROTECTION_EXIT, FEED_FAILURE -> 0.002;
            default -> 0.006;
        };
    }

    public record SimulatedBar(
            Instant openTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume
    ) {
    }

    public record SimulatedMarketSession(
            String symbol,
            BigDecimal basePrice,
            List<SimulatedBar> equityBars,
            List<SimulatedBar> niftyBars,
            Instant anchor
    ) {
    }
}
