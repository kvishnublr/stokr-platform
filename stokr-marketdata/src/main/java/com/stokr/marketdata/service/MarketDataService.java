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
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * When false (default), raw ticks are NOT written to marketdata_ticks.
     * The price cache and 1m candle are still updated — strategies use candles, not raw ticks.
     * At 2000 symbols × 1 tick/sec this saves ~45M rows/day in DB writes.
     */
    @Value("${stokr.marketdata.persist-ticks:false}")
    private boolean persistTicks;

    /**
     * Ingest a single tick: update the price cache and aggregate into the in-memory 1m candle.
     * DB write is intentionally deferred to {@link #flushDirtyCandles()} — called every 500ms.
     * This avoids one DB transaction per tick, which exhausted the HikariCP pool under live load.
     */
    @Transactional
    public MarketdataTick ingestTick(MarketdataTick tick, ZoneId zone) {
        if (persistTicks) {
            tick = tickRepository.save(tick);
        }
        latestPriceCache.setLastPrice(tick.getSymbol(), tick.getPrice());
        candleAggregator.applyTick(tick, zone);
        return tick;
    }

    /**
     * Flush all in-memory candle state to the DB using a single-statement native UPSERT per symbol/minute.
     * Runs every 500ms (configurable). With 3000 live symbols this replaces ~15000 per-tick transactions
     * with ~3000 UPSERT statements inside one transaction every 500ms — a ~7500x reduction in DB round-trips.
     */
    @Scheduled(fixedDelayString = "${stokr.marketdata.candle-flush-ms:500}")
    @Transactional
    public void flushDirtyCandles() {
        List<MarketdataCandle> dirty = candleAggregator.snapshotDirtyAndClear();
        if (dirty.isEmpty()) return;
        for (MarketdataCandle c : dirty) {
            candleRepository.upsertCandle(
                    c.getSymbol(), c.getTimeframe(), c.getOpenTime(),
                    c.getOpenPrice(), c.getHighPrice(), c.getLowPrice(),
                    c.getClosePrice(), safe(c.getVolume()));
        }
        log.debug("marketdata.candle_flush count={}", dirty.size());
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
