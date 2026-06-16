package com.stokr.strategy.service;

import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.service.MarketDataQueryService;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Fills entry / stop / target on signals that only carry direction + reason (catalog, replay).
 * Required for {@link SignalOutcomeTrackerService} and admin PnL display.
 */
@Service
@RequiredArgsConstructor
public class SignalPriceEnrichmentService {

    private static final int ATR_PERIOD = 14;
    private static final double STOP_ATR_MULT = 1.5;
    private static final double TARGET_ATR_MULT = 2.5;

    private final MarketDataQueryService marketDataQueryService;
    private final InstrumentNormalizationService instrumentNormalizationService;

    /**
     * Applies broker fill price then derives stop/target when still missing (live entry path).
     */
    public void enrichOnEntryFill(StrategySignalEntity entity, BigDecimal fillPrice, Instant asOf) {
        if (entity == null) {
            return;
        }
        if (fillPrice != null && fillPrice.signum() > 0) {
            BigDecimal scaled = fillPrice.setScale(4, RoundingMode.HALF_UP);
            entity.setEntryReferencePrice(scaled);
            entity.setEntryPrice(scaled);
        }
        enrichIfMissing(entity, asOf);
    }

    public void enrichIfMissing(StrategySignalEntity entity, Instant asOf) {
        if (entity == null || entity.getSymbol() == null || entity.getSignalType() == null) {
            return;
        }
        if (entity.getEntryReferencePrice() != null
                && entity.getStopPrice() != null
                && entity.getTargetPrice() != null) {
            return;
        }

        Instant ref = asOf != null ? asOf : Instant.now();
        String normalizedSymbol = instrumentNormalizationService.normalizeForMarketData(entity.getSymbol());
        if (normalizedSymbol == null) {
            return;
        }

        List<MarketdataCandle> bars = marketDataQueryService.lastBarsAscEndingAt(
                normalizedSymbol, "1m", 40, ref);
        if (bars.size() < 3) {
            bars = marketDataQueryService.lastBarsAscEndingAt(normalizedSymbol, "5m", 40, ref);
        }
        if (bars.isEmpty()) {
            return;
        }

        MarketdataCandle last = bars.get(bars.size() - 1);
        if (last.getClosePrice() == null || last.getClosePrice().signum() <= 0) {
            return;
        }

        BigDecimal entry = last.getClosePrice().setScale(4, RoundingMode.HALF_UP);
        double atr = computeAtr(bars);
        if (atr <= 0) {
            atr = entry.doubleValue() * 0.008;
        }

        boolean buy = entity.getSignalType() == SignalType.BUY;
        BigDecimal stopDist = BigDecimal.valueOf(atr * STOP_ATR_MULT).setScale(4, RoundingMode.HALF_UP);
        BigDecimal tgtDist = BigDecimal.valueOf(atr * TARGET_ATR_MULT).setScale(4, RoundingMode.HALF_UP);

        BigDecimal sl;
        BigDecimal tgt;
        if (buy) {
            sl = entry.subtract(stopDist);
            tgt = entry.add(tgtDist);
        } else {
            sl = entry.add(stopDist);
            tgt = entry.subtract(tgtDist);
        }

        if (entity.getEntryReferencePrice() == null) {
            entity.setEntryReferencePrice(entry);
        }
        if (entity.getStopPrice() == null) {
            entity.setStopPrice(sl.max(BigDecimal.valueOf(0.01)));
        }
        if (entity.getTargetPrice() == null) {
            entity.setTargetPrice(tgt.max(BigDecimal.valueOf(0.01)));
        }
        if (entity.getEntryPrice() == null) {
            entity.setEntryPrice(entry);
        }
    }

    private static double computeAtr(List<MarketdataCandle> bars) {
        int n = bars.size();
        if (n < 2) {
            return 0;
        }
        int start = Math.max(1, n - ATR_PERIOD);
        double sum = 0;
        int count = 0;
        for (int i = start; i < n; i++) {
            MarketdataCandle cur = bars.get(i);
            MarketdataCandle prev = bars.get(i - 1);
            if (cur.getHighPrice() == null || cur.getLowPrice() == null || prev.getClosePrice() == null) {
                continue;
            }
            double tr = Math.max(
                    cur.getHighPrice().doubleValue() - cur.getLowPrice().doubleValue(),
                    Math.max(
                            Math.abs(cur.getHighPrice().doubleValue() - prev.getClosePrice().doubleValue()),
                            Math.abs(cur.getLowPrice().doubleValue() - prev.getClosePrice().doubleValue())));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }
}
