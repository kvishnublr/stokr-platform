package com.stokr.strategy.ematrend;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmaTrendFollowingSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.ema.fast:9}")
    private int fast;

    @Value("${stokr.ema.slow:21}")
    private int slow;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 400, barOpenTime);
        if (bars.size() < slow + 5) {
            return null;
        }
        ZonedDateTime z = barOpenTime.atZone(zone);
        LocalTime lt = z.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
            return null;
        }
        List<BigDecimal> closes = new ArrayList<>();
        for (MarketdataCandle c : bars) {
            closes.add(c.getClosePrice());
        }
        BigDecimal emaFast = emaAt(closes, fast, closes.size() - 1);
        BigDecimal emaSlow = emaAt(closes, slow, closes.size() - 1);
        BigDecimal prevFast = emaAt(closes, fast, closes.size() - 2);
        BigDecimal prevSlow = emaAt(closes, slow, closes.size() - 2);
        if (emaFast == null || emaSlow == null || prevFast == null || prevSlow == null) {
            return null;
        }
        boolean goldenCross = emaFast.compareTo(emaSlow) > 0 && prevFast.compareTo(prevSlow) <= 0;
        boolean deathCross  = emaFast.compareTo(emaSlow) < 0 && prevFast.compareTo(prevSlow) >= 0;

        if (!goldenCross && !deathCross) {
            return null;
        }

        MarketdataCandle last = bars.getLast();
        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.EMA_TREND_FOLLOW);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSuggestedQty(BigDecimal.ONE);
        sig.setEntryReferencePrice(last.getClosePrice());
        sig.setConfidenceScore(new BigDecimal("0.61"));

        if (goldenCross) {
            sig.setSignalType(SignalType.BUY);
            sig.setReasonText("EMA golden cross: EMA" + fast + " crossed above EMA" + slow);
            sig.setStopPrice(emaSlow);
            sig.setTargetPrice(last.getClosePrice().add(
                    last.getClosePrice().subtract(emaSlow).multiply(new BigDecimal("1.5"), MC)));
        } else {
            sig.setSignalType(SignalType.SELL);
            sig.setReasonText("EMA death cross: EMA" + fast + " crossed below EMA" + slow);
            sig.setStopPrice(emaSlow);
            sig.setTargetPrice(last.getClosePrice().subtract(
                    emaSlow.subtract(last.getClosePrice()).multiply(new BigDecimal("1.5"), MC)));
        }
        return sig;
    }

    private static BigDecimal emaAt(List<BigDecimal> closes, int period, int idx) {
        if (idx < period - 1) {
            return null;
        }
        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), MC);
        BigDecimal ema = sma(closes.subList(0, period));
        for (int i = period; i <= idx; i++) {
            ema = closes.get(i).subtract(ema).multiply(k).add(ema);
        }
        return ema;
    }

    private static BigDecimal sma(List<BigDecimal> xs) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal x : xs) {
            s = s.add(x);
        }
        return s.divide(BigDecimal.valueOf(xs.size()), MC);
    }
}
