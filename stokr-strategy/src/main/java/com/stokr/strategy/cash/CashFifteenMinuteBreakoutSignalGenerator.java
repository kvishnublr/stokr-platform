package com.stokr.strategy.cash;

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

/**
 * Minimal deterministic 15m cash breakout strategy for E2E pipeline testing.
 * Intended for paper-mode validation, not production alpha.
 */
@Service
@RequiredArgsConstructor
public class CashFifteenMinuteBreakoutSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.cash15m.session.start:09:30}")
    private LocalTime sessionStart;

    @Value("${stokr.cash15m.session.end:15:00}")
    private LocalTime sessionEnd;

    @Value("${stokr.cash15m.lookback-bars:12}")
    private int lookbackBars;

    @Value("${stokr.cash15m.min-breakout-pct:0.0015}")
    private BigDecimal minBreakoutPct;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, "15m", 80, barOpenTime);
        if (bars.size() < lookbackBars + 3) {
            return null;
        }
        ZonedDateTime z = barOpenTime.atZone(zone);
        LocalTime lt = z.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
            return null;
        }

        MarketdataCandle last = bars.getLast();
        MarketdataCandle prev = bars.get(bars.size() - 2);
        int from = Math.max(0, bars.size() - 1 - lookbackBars);
        BigDecimal priorHigh = bars.subList(from, bars.size() - 1).stream()
                .map(MarketdataCandle::getHighPrice)
                .max(BigDecimal::compareTo)
                .orElse(null);
        BigDecimal priorLow = bars.subList(from, bars.size() - 1).stream()
                .map(MarketdataCandle::getLowPrice)
                .min(BigDecimal::compareTo)
                .orElse(null);
        if (priorHigh == null || priorLow == null || last.getClosePrice() == null || prev.getClosePrice() == null) {
            return null;
        }

        BigDecimal thresholdUp = priorHigh.multiply(BigDecimal.ONE.add(minBreakoutPct), MC);
        BigDecimal thresholdDn = priorLow.multiply(BigDecimal.ONE.subtract(minBreakoutPct), MC);

        boolean breakUp = last.getClosePrice().compareTo(thresholdUp) > 0 && prev.getClosePrice().compareTo(priorHigh) <= 0;
        boolean breakDown = last.getClosePrice().compareTo(thresholdDn) < 0 && prev.getClosePrice().compareTo(priorLow) >= 0;
        if (!breakUp && !breakDown) {
            return null;
        }

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.CASH_15M_BREAKOUT_TEST);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSignalType(breakUp ? SignalType.BUY : SignalType.SELL);
        sig.setConfidenceScore(new BigDecimal("0.60"));
        sig.setReasonText("15m breakout test signal");
        sig.setEntryReferencePrice(last.getClosePrice());
        sig.setSuggestedQty(BigDecimal.ONE);

        BigDecimal atrProxy = last.getHighPrice().subtract(last.getLowPrice()).abs().max(new BigDecimal("0.20"));
        if (breakUp) {
            sig.setStopPrice(last.getClosePrice().subtract(atrProxy, MC));
            sig.setTargetPrice(last.getClosePrice().add(atrProxy.multiply(new BigDecimal("1.8"), MC), MC));
        } else {
            sig.setStopPrice(last.getClosePrice().add(atrProxy, MC));
            sig.setTargetPrice(last.getClosePrice().subtract(atrProxy.multiply(new BigDecimal("1.8"), MC), MC));
        }
        return sig;
    }
}

