package com.stokr.strategy.momentum;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.keys.StrategyKeys;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MomentumBreakoutSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.momentum.lookback:20}")
    private int lookback;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 320, barOpenTime);
        if (bars.size() < lookback + 3) {
            return null;
        }
        ZonedDateTime z = barOpenTime.atZone(zone);
        LocalTime lt = z.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
            return null;
        }
        MarketdataCandle last = bars.getLast();
        MarketdataCandle prev = bars.get(bars.size() - 2);
        int from = Math.max(0, bars.size() - 1 - lookback);
        BigDecimal priorHigh = bars.subList(from, bars.size() - 1).stream()
                .map(MarketdataCandle::getHighPrice)
                .max(BigDecimal::compareTo)
                .orElse(null);
        if (priorHigh == null) {
            return null;
        }
        BigDecimal priorLow = bars.subList(from, bars.size() - 1).stream()
                .map(MarketdataCandle::getLowPrice)
                .min(BigDecimal::compareTo)
                .orElse(null);

        boolean bullBreakout = last.getClosePrice().compareTo(priorHigh) > 0
                && prev.getClosePrice().compareTo(priorHigh) <= 0;
        boolean bearBreakout = priorLow != null
                && last.getClosePrice().compareTo(priorLow) < 0
                && prev.getClosePrice().compareTo(priorLow) >= 0;

        if (!bullBreakout && !bearBreakout) {
            return null;
        }

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.MOMENTUM_BREAKOUT);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSuggestedQty(BigDecimal.ONE);
        sig.setEntryReferencePrice(last.getClosePrice());

        if (bullBreakout) {
            BigDecimal risk = last.getClosePrice().subtract(last.getLowPrice()).abs().max(new BigDecimal("0.5"));
            sig.setSignalType(SignalType.BUY);
            sig.setConfidenceScore(new BigDecimal("0.64"));
            sig.setReasonText("Momentum bullish breakout above prior range high");
            sig.setStopPrice(last.getLowPrice());
            sig.setTargetPrice(last.getClosePrice().add(risk.multiply(new BigDecimal("2"), MC)));
        } else {
            BigDecimal risk = last.getHighPrice().subtract(last.getClosePrice()).abs().max(new BigDecimal("0.5"));
            sig.setSignalType(SignalType.SELL);
            sig.setConfidenceScore(new BigDecimal("0.64"));
            sig.setReasonText("Momentum bearish breakdown below prior range low");
            sig.setStopPrice(last.getHighPrice());
            sig.setTargetPrice(last.getClosePrice().subtract(risk.multiply(new BigDecimal("2"), MC)));
        }
        return sig;
    }
}
