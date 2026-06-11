package com.stokr.strategy.generated;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.catalog.GeneratedStrategy;
import com.stokr.strategy.context.StrategyContext;
import com.stokr.strategy.engine.TradingStrategy;
import com.stokr.strategy.integrity.StrategyGeneratorIntegrityGate;
import com.stokr.strategy.signals.SignalType;
import com.stokr.strategy.signals.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDS USDINR momentum: consecutive directional 5m bars with volume confirmation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
        strategyKey = "USDINR_MOMENTUM",
        assetClass = "CURRENCY",
        segment = "CDS",
        exchange = "CDS",
        timeframe = "5m"
)
public class UsdInrMomentumSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "5m";
    private static final int MIN_BARS = 12;

    private final MarketDataQueryService marketDataQueryService;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.usdinr-momentum.min-momentum-pct:0.03}")
    private double minMomentumPct;

    @Value("${stokr.strategy.usdinr-momentum.stop-loss-pct:0.80}")
    private double stopLossPct;  // 0.80% SL for tight risk control

    @Value("${stokr.strategy.usdinr-momentum.profit-target-pct:1.50}")
    private double profitTargetPct;  // 1.50% target (RR = 1.88×)

    @Value("${stokr.strategy.usdinr-momentum.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() {
        return "USDINR_MOMENTUM";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        if (symbol == null || !symbol.toUpperCase().contains("USDINR")) {
            return hold(context);
        }

        Instant asOf = context.asOf() != null ? context.asOf() : Instant.now();
        if (!integrityGate.passPreEvaluate(key(), symbol, asOf)) {
            return hold(context);
        }

        Instant last = lastEmitBySymbol.get(symbol);
        if (last != null && Duration.between(last, asOf).getSeconds() < cooldownSeconds) {
            return hold(context);
        }

        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAsc(symbol, TIMEFRAME, MIN_BARS);
        if (bars == null || bars.size() < MIN_BARS) {
            return hold(context);
        }

        double[] closes = bars.stream().mapToDouble(c -> c.getClosePrice().doubleValue()).toArray();
        double mom3 = pctChange(closes[closes.length - 1], closes[closes.length - 4]);
        double mom6 = pctChange(closes[closes.length - 1], closes[closes.length - 7]);
        double avgVol = averageVolume(bars, bars.size() - 4, bars.size() - 1);
        double lastVol = bars.get(bars.size() - 1).getVolume().doubleValue();

        if (mom3 > minMomentumPct && mom6 > 0 && lastVol >= avgVol * 0.8) {
            lastEmitBySymbol.put(symbol, asOf);
            double currentPrice = closes[closes.length - 1];
            double stopLoss = currentPrice * (1.0 - stopLossPct / 100.0);
            double target = currentPrice * (1.0 + profitTargetPct / 100.0);
            log.info("usdinr_momentum.buy symbol={} mom3={} mom6={} entry={} sl={} target={}",
                symbol, mom3, mom6, currentPrice, stopLoss, target);
            return new StrategySignal(
                    SignalType.BUY,
                    symbol,
                    BigDecimal.ONE,
                    String.format("USDINR momentum continuation (SL:%.2f, Target:%.2f)", stopLoss, target),
                    BigDecimal.valueOf(currentPrice),
                    BigDecimal.valueOf(stopLoss),
                    BigDecimal.valueOf(target)
            );
        }
        if (mom3 < -minMomentumPct && mom6 < 0 && lastVol >= avgVol * 0.8) {
            lastEmitBySymbol.put(symbol, asOf);
            double currentPrice = closes[closes.length - 1];
            double stopLoss = currentPrice * (1.0 + stopLossPct / 100.0);
            double target = currentPrice * (1.0 - profitTargetPct / 100.0);
            log.info("usdinr_momentum.sell symbol={} mom3={} mom6={} entry={} sl={} target={}",
                symbol, mom3, mom6, currentPrice, stopLoss, target);
            return new StrategySignal(
                    SignalType.SELL,
                    symbol,
                    BigDecimal.ONE,
                    String.format("USDINR momentum breakdown (SL:%.2f, Target:%.2f)", stopLoss, target),
                    BigDecimal.valueOf(currentPrice),
                    BigDecimal.valueOf(stopLoss),
                    BigDecimal.valueOf(target)
            );
        }
        return hold(context);
    }

    private static double pctChange(double latest, double prior) {
        if (prior == 0) {
            return 0;
        }
        return (latest - prior) / prior * 100.0;
    }

    private static double averageVolume(List<MarketdataCandle> bars, int from, int toInclusive) {
        double sum = 0;
        int n = 0;
        for (int i = from; i <= toInclusive && i < bars.size(); i++) {
            sum += bars.get(i).getVolume().doubleValue();
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }
}
