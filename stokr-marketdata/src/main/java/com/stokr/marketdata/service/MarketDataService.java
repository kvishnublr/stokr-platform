package com.stokr.marketdata.service;

import com.stokr.marketdata.cache.LatestPriceCache;
import com.stokr.marketdata.domain.MarketdataCandle;
import com.stokr.marketdata.domain.MarketdataTick;
import com.stokr.marketdata.repository.MarketdataCandleRepository;
import com.stokr.marketdata.repository.MarketdataTickRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataService {

    private final MarketdataTickRepository tickRepository;
    private final MarketdataCandleRepository candleRepository;
    private final CandleAggregator candleAggregator;
    private final LatestPriceCache latestPriceCache;
    private final TransactionTemplate txTemplate;

    @Value("${stokr.marketdata.persist-ticks:false}")
    private boolean persistTicks;

    /**
     * Ingest a single tick: update Redis price cache and aggregate into the in-memory 1m candle.
     * No DB connection acquired — persistTicks is false by default (tickRepository.save has its own tx).
     */
    public MarketdataTick ingestTick(MarketdataTick tick, ZoneId zone) {
        if (persistTicks) {
            tick = tickRepository.save(tick);
        }
        latestPriceCache.setLastPrice(tick.getSymbol(), tick.getPrice());
        candleAggregator.applyTick(tick, zone);
        return tick;
    }

    /**
     * Flush dirty candles to DB every 500ms.
     * No DB connection is acquired when there are no dirty candles — the empty check runs before
     * the transaction opens. Previously @Transactional on the whole method acquired a connection
     * 2x/second even outside market hours, exhausting the pool.
     */
    @Scheduled(fixedDelayString = "${stokr.marketdata.candle-flush-ms:500}")
    public void flushDirtyCandles() {
        List<MarketdataCandle> dirty = candleAggregator.snapshotDirtyAndClear();
        if (dirty.isEmpty()) return;
        txTemplate.executeWithoutResult(status -> {
            for (MarketdataCandle c : dirty) {
                candleRepository.upsertCandle(
                        c.getSymbol(), c.getTimeframe(), c.getOpenTime(),
                        c.getOpenPrice(), c.getHighPrice(), c.getLowPrice(),
                        c.getClosePrice(), safe(c.getVolume()));
            }
        });
        log.debug("marketdata.candle_flush count={}", dirty.size());
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
