package com.stokr.strategy.vwap;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VwapMeanReversionSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.strategy.session.end:14:45}")
    private LocalTime sessionEnd;

    @Value("${stokr.vwap.deviation-pct:0.004}")
    private BigDecimal deviationPct;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 240, barOpenTime);
        if (bars.size() < 40) {
            return null;
        }
        MarketdataCandle last = bars.getLast();
        ZonedDateTime eval = barOpenTime.atZone(zone);
        LocalTime lt = eval.toLocalTime();
        if (lt.isBefore(sessionStart) || lt.isAfter(sessionEnd)) {
            return null;
        }
        LocalDate day = eval.toLocalDate();
        Instant open = day.atTime(sessionStart).atZone(zone).toInstant();
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal vol = BigDecimal.ZERO;
        for (MarketdataCandle c : bars) {
            if (c.getOpenTime().isBefore(open)) {
                continue;
            }
            if (c.getOpenTime().isAfter(barOpenTime)) {
                break;
            }
            if (c.getHighPrice() == null || c.getLowPrice() == null || c.getClosePrice() == null) {
                continue;
            }
            BigDecimal tp = c.getHighPrice().add(c.getLowPrice()).add(c.getClosePrice())
                    .divide(BigDecimal.valueOf(3), MC);
            BigDecimal v = c.getVolume() != null ? c.getVolume() : BigDecimal.ONE;
            pv = pv.add(tp.multiply(v));
            vol = vol.add(v);
        }
        if (vol.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal vwap = pv.divide(vol, MC);
        BigDecimal dist = last.getClosePrice().subtract(vwap).divide(last.getClosePrice(), MC).abs();
        if (dist.compareTo(deviationPct) < 0) {
            return null;
        }
        boolean below = last.getClosePrice().compareTo(vwap) < 0;
        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.VWAP_MEAN_REVERSION);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSignalType(below ? SignalType.BUY : SignalType.SELL);
        sig.setConfidenceScore(new BigDecimal("0.62"));
        sig.setReasonText(below ? "Fade below VWAP" : "Fade above VWAP");
        sig.setEntryReferencePrice(last.getClosePrice());
        sig.setSuggestedQty(BigDecimal.ONE);
        sig.setStopPrice(below ? last.getLowPrice() : last.getHighPrice());
        sig.setTargetPrice(vwap);
        return sig;
    }
}
