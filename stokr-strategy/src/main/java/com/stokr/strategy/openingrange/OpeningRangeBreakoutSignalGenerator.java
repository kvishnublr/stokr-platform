package com.stokr.strategy.openingrange;

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

/**
 * Opening range breakout / breakdown after an initial window from session start (deterministic on candle boundaries).
 */
@Service
@RequiredArgsConstructor
public class OpeningRangeBreakoutSignalGenerator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final MarketDataQueryService marketDataQueryService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Value("${stokr.strategy.session.start:09:25}")
    private LocalTime sessionStart;

    @Value("${stokr.orb.range-minutes:15}")
    private int rangeMinutes;

    public StrategySignalEntity evaluatePersistableAtOpen(
            String symbol,
            UUID userId,
            UUID backtestRunId,
            String pipeline,
            Instant barOpenTime,
            String timeframe
    ) {
        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(symbol, timeframe, 240, barOpenTime);
        if (bars.size() < 10) {
            return null;
        }
        MarketdataCandle last = bars.getLast();
        ZonedDateTime z = barOpenTime.atZone(zone);
        LocalDate day = z.toLocalDate();
        LocalTime lt = z.toLocalTime();
        Instant sessionOpen = day.atTime(sessionStart).atZone(zone).toInstant();
        Instant rangeEnd = sessionOpen.plusSeconds(rangeMinutes * 60L);

        if (!barOpenTime.isBefore(sessionOpen) && !barOpenTime.isAfter(rangeEnd)) {
            return null;
        }
        if (lt.isBefore(sessionStart)) {
            return null;
        }

        List<MarketdataCandle> rangeBars = bars.stream()
                .filter(c -> !c.getOpenTime().isBefore(sessionOpen) && !c.getOpenTime().isAfter(rangeEnd))
                .toList();
        if (rangeBars.size() < 3) {
            return null;
        }
        BigDecimal orHigh = rangeBars.stream().map(MarketdataCandle::getHighPrice).max(BigDecimal::compareTo).orElse(null);
        BigDecimal orLow = rangeBars.stream().map(MarketdataCandle::getLowPrice).min(BigDecimal::compareTo).orElse(null);
        if (orHigh == null || orLow == null) {
            return null;
        }

        MarketdataCandle prev = bars.size() >= 2 ? bars.get(bars.size() - 2) : null;
        if (prev == null) {
            return null;
        }

        boolean breakUp = last.getClosePrice().compareTo(orHigh) > 0 && prev.getClosePrice().compareTo(orHigh) <= 0;
        boolean breakDown = last.getClosePrice().compareTo(orLow) < 0 && prev.getClosePrice().compareTo(orLow) >= 0;

        if (!breakUp && !breakDown) {
            return null;
        }

        StrategySignalEntity sig = new StrategySignalEntity();
        sig.setStrategyName(StrategyKeys.OPENING_RANGE_BREAKOUT);
        sig.setStrategyVersion(StrategySignalEntity.VERSION);
        sig.setSymbol(symbol);
        sig.setUserId(userId);
        sig.setBacktestRunId(backtestRunId);
        sig.setPipeline(pipeline);
        sig.setCandleTimestamp(last.getOpenTime());
        sig.setSignalType(breakUp ? SignalType.BUY : SignalType.SELL);
        sig.setConfidenceScore(new BigDecimal("0.65"));
        sig.setReasonText(breakUp ? "ORB break up" : "ORB break down");
        sig.setEntryReferencePrice(last.getClosePrice());
        sig.setSuggestedQty(BigDecimal.ONE);
        BigDecimal stop = breakUp ? orLow : orHigh;
        sig.setStopPrice(stop);
        sig.setTargetPrice(breakUp
                ? last.getClosePrice().add(last.getClosePrice().subtract(orLow).multiply(new BigDecimal("1.5"), MC))
                : last.getClosePrice().subtract(orHigh.subtract(last.getClosePrice()).multiply(new BigDecimal("1.5"), MC)));
        return sig;
    }
}
