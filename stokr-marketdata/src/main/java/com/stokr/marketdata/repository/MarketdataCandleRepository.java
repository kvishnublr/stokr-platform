package com.stokr.marketdata.repository;

import com.stokr.marketdata.domain.MarketdataCandle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketdataCandleRepository extends JpaRepository<MarketdataCandle, UUID> {

    Optional<MarketdataCandle> findBySymbolAndTimeframeAndOpenTimeAndDeletedFalse(
            String symbol,
            String timeframe,
            Instant openTime
    );

    List<MarketdataCandle> findTop500BySymbolAndTimeframeAndDeletedFalseOrderByOpenTimeDesc(String symbol, String tf);

    List<MarketdataCandle> findBySymbolAndTimeframeAndOpenTimeBetweenAndDeletedFalseOrderByOpenTimeAsc(
            String symbol,
            String timeframe,
            Instant start,
            Instant end
    );
}
