package com.stokr.marketdata.repository;

import com.stokr.marketdata.domain.MarketdataCandle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketdataCandleRepository extends JpaRepository<MarketdataCandle, UUID> {

    /**
     * Batch-friendly UPSERT: inserts a new candle row, or merges into the existing one.
     * OHLC: open is kept from the first insert; high/low use GREATEST/LEAST so partial
     * in-memory state never shrinks the range; close and volume are always overwritten
     * with the latest accumulated values from CandleAggregator.
     */
    @Modifying
    @Query(value = """
            INSERT INTO marketdata_candles
                (id, created_at, updated_at, version, deleted,
                 symbol, timeframe, open_time,
                 open_price, high_price, low_price, close_price, volume)
            VALUES
                (gen_random_uuid(), NOW(), NOW(), 0, FALSE,
                 :symbol, :timeframe, :openTime,
                 :openPrice, :highPrice, :lowPrice, :closePrice, :volume)
            ON CONFLICT (symbol, timeframe, open_time) DO UPDATE SET
                high_price  = GREATEST(marketdata_candles.high_price, EXCLUDED.high_price),
                low_price   = LEAST(marketdata_candles.low_price, EXCLUDED.low_price),
                close_price = EXCLUDED.close_price,
                volume      = EXCLUDED.volume,
                updated_at  = NOW(),
                version     = marketdata_candles.version + 1
            """, nativeQuery = true)
    void upsertCandle(@Param("symbol") String symbol,
                      @Param("timeframe") String timeframe,
                      @Param("openTime") Instant openTime,
                      @Param("openPrice") BigDecimal openPrice,
                      @Param("highPrice") BigDecimal highPrice,
                      @Param("lowPrice") BigDecimal lowPrice,
                      @Param("closePrice") BigDecimal closePrice,
                      @Param("volume") BigDecimal volume);

    Optional<MarketdataCandle> findTopBySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(
            String symbol, String timeframe);

    Optional<MarketdataCandle> findTopBySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeAsc(
            String symbol, String timeframe);

    Optional<MarketdataCandle> findBySymbolAndTimeframeAndOpenTimeAndDeletedFalse(
            String symbol,
            String timeframe,
            Instant openTime
    );

    List<MarketdataCandle> findTop500BySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(String symbol, String tf);

    List<MarketdataCandle> findTop500BySymbolInAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(List<String> symbols, String tf);

    List<MarketdataCandle> findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
            String symbol,
            String timeframe,
            Instant start,
            Instant end
    );

    List<MarketdataCandle> findBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
            List<String> symbols,
            String timeframe,
            Instant start,
            Instant end
    );

    Page<MarketdataCandle> findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
            String symbol,
            String timeframe,
            Instant start,
            Instant end,
            Pageable pageable
    );

    long countBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalse(
            String symbol,
            String timeframe,
            Instant start,
            Instant end
    );

    long countBySymbolInAndTimeframeAndOpenTimeBetweenAndDeletedFalse(
            List<String> symbols,
            String timeframe,
            Instant start,
            Instant end
    );

    /**
     * Newest candles first, {@code open_time <= endInclusive}, for replay lookbacks (not "global tail then filter").
     */
    Page<MarketdataCandle> findBySymbolAndTimeframeAndOpenTimeLessThanEqualAndDeletedFalse(
            String symbol,
            String timeframe,
            Instant endInclusive,
            Pageable pageable
    );

    Page<MarketdataCandle> findBySymbolInAndTimeframeAndOpenTimeLessThanEqualAndDeletedFalse(
            List<String> symbols,
            String timeframe,
            Instant endInclusive,
            Pageable pageable
    );
}
