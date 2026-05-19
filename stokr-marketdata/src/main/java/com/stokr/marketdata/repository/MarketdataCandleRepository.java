package com.stokr.marketdata.repository;

import com.stokr.marketdata.domain.MarketdataCandle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketdataCandleRepository extends JpaRepository<MarketdataCandle, UUID> {

    Optional<MarketdataCandle> findTopBySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(
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
