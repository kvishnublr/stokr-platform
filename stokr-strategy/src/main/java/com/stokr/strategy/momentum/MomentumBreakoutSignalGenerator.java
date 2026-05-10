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
        boolean breakout = last.getClosePrice().compareTo(priorHigh) > 0 && prev.getClosePrice().compareTo(priorHigh) <= 0;
        if (!breakout) {
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
        sig.setSignalType(SignalType.BUY);
        sig.setConfidenceScore(new BigDecimal("0.64"));
        sig.setReasonText("Momentum breakout prior range high");
        sig.setEntryReferencePrice(last.getClosePrice());
        sig.setSuggestedQty(BigDecimal.ONE);
        BigDecimal risk = last.getClosePrice().subtract(last.getLowPrice()).abs().max(new BigDecimal("0.5"));
        sig.setStopPrice(last.getLowPrice());
        sig.setTargetPrice(last.getClosePrice().add(risk.multiply(new BigDecimal("2"), MC)));
        return sig;
    }
}
