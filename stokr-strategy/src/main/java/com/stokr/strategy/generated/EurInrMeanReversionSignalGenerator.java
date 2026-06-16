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
 * CDS EURINR mean reversion: fade short-term stretch back toward 5m SMA.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@GeneratedStrategy(
        strategyKey = "EURINR_MEAN_REVERSION",
        assetClass = "CURRENCY",
        segment = "CDS",
        exchange = "CDS",
        timeframe = "5m"
)
public class EurInrMeanReversionSignalGenerator extends BaseGeneratedStrategy implements TradingStrategy {

    private static final String TIMEFRAME = "5m";
    private static final int MIN_BARS = 20;

    private final MarketDataQueryService marketDataQueryService;
    private final StrategyGeneratorIntegrityGate integrityGate;
    private final ConcurrentHashMap<String, Instant> lastEmitBySymbol = new ConcurrentHashMap<>();

    @Value("${stokr.strategy.eurinr-mean-reversion.stretch-pct:0.06}")
    private double stretchPct;

    @Value("${stokr.strategy.eurinr-mean-reversion.stop-loss-pct:0.80}")
    private double stopLossPct;  // 0.80% SL for tight risk control

    @Value("${stokr.strategy.eurinr-mean-reversion.profit-target-pct:1.50}")
    private double profitTargetPct;  // 1.50% target (RR = 1.88??)

    @Value("${stokr.strategy.eurinr-mean-reversion.cooldown-seconds:900}")
    private int cooldownSeconds;

    @Override
    public String key() {
        return "EURINR_MEAN_REVERSION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        String symbol = context.symbol();
        if (symbol == null || !symbol.toUpperCase().contains("EURINR")) {
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
        double sma = sma(closes, 10);
        double lastClose = closes[closes.length - 1];
        if (Double.isNaN(sma) || sma == 0) {
            return hold(context);
        }

        double deviationPct = (lastClose - sma) / sma * 100.0;
        double rsi = rsi(closes, 14);

        if (deviationPct <= -stretchPct && rsi < 35) {
            lastEmitBySymbol.put(symbol, asOf);
            double currentPrice = lastClose;
            double stopLoss = currentPrice * (1.0 - stopLossPct / 100.0);
            double target = currentPrice * (1.0 + profitTargetPct / 100.0);
            log.info("eurinr_mean_reversion.buy symbol={} dev={} rsi={} entry={} sl={} target={}",
                symbol, deviationPct, rsi, currentPrice, stopLoss, target);
            return new StrategySignal(
                    SignalType.BUY,
                    symbol,
                    BigDecimal.ONE,
                    String.format("EURINR oversold mean reversion (SL:%.2f, Target:%.2f)", stopLoss, target),
                    BigDecimal.valueOf(currentPrice),
                    BigDecimal.valueOf(stopLoss),
                    BigDecimal.valueOf(target)
            );
        }
        if (deviationPct >= stretchPct && rsi > 65) {
            lastEmitBySymbol.put(symbol, asOf);
            double currentPrice = lastClose;
            double stopLoss = currentPrice * (1.0 + stopLossPct / 100.0);
            double target = currentPrice * (1.0 - profitTargetPct / 100.0);
            log.info("eurinr_mean_reversion.sell symbol={} dev={} rsi={} entry={} sl={} target={}",
                symbol, deviationPct, rsi, currentPrice, stopLoss, target);
            return new StrategySignal(
                    SignalType.SELL,
                    symbol,
                    BigDecimal.ONE,
                    String.format("EURINR overbought mean reversion (SL:%.2f, Target:%.2f)", stopLoss, target),
                    BigDecimal.valueOf(currentPrice),
                    BigDecimal.valueOf(stopLoss),
                    BigDecimal.valueOf(target)
            );
        }
        return hold(context);
    }
}
