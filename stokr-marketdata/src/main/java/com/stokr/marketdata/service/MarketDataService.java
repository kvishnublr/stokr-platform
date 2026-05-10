package com.stokr.marketdata.service;

import com.stokr.marketdata.cache.LatestPriceCache;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import lombok.RequiredArgsConstructor;
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

    @Transactional
    public MarketdataTick ingestTick(MarketdataTick tick, ZoneId zone) {
        MarketdataTick saved = tickRepository.save(tick);
        latestPriceCache.setLastPrice(saved.getSymbol(), saved.getPrice());

        MarketdataCandle partial = candleAggregator.applyTick(saved, zone);
        upsertOneMinuteCandle(partial);
        return saved;
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
