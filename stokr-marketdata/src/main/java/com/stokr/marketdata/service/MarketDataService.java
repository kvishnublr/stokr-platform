package com.stokr.marketdata.service;

import com.stokr.marketdata.cache.LatestPriceCache;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final MarketdataTickRepository tickRepository;
    private final MarketdataCandleRepository candleRepository;
    private final CandleAggregator candleAggregator;
    private final LatestPriceCache latestPriceCache;

    /**
     * When false (default), raw ticks are NOT written to marketdata_ticks.
     * The price cache and 1m candle are still updated — strategies use candles, not raw ticks.
     * At 2000 symbols × 1 tick/sec this saves ~45M rows/day in DB writes.
     */
    @Value("${stokr.marketdata.persist-ticks:false}")
    private boolean persistTicks;

    @Transactional
    public MarketdataTick ingestTick(MarketdataTick tick, ZoneId zone) {
        if (persistTicks) {
            tick = tickRepository.save(tick);
        }
        latestPriceCache.setLastPrice(tick.getSymbol(), tick.getPrice());
        MarketdataCandle partial = candleAggregator.applyTick(tick, zone);
        upsertOneMinuteCandle(partial);
        return tick;
    }

    private void upsertOneMinuteCandle(MarketdataCandle candle) {
        candleRepository
                .findBySymbolAndTimeframeAndOpenTimeAndDeletedFalse(candle.getSymbol(), candle.getTimeframe(), candle.getOpenTime())
                .ifPresentOrElse(
                        existing -> {
                            existing.setHighPrice(existing.getHighPrice().max(candle.getHighPrice()));
                            existing.setLowPrice(existing.getLowPrice().min(candle.getLowPrice()));
                            existing.setClosePrice(candle.getClosePrice());
                            existing.setVolume(safe(existing.getVolume()).add(safe(candle.getVolume())));
                            candleRepository.save(existing);
                        },
                        () -> candleRepository.save(candle)
                );
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
