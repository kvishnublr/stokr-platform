package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleDataRepository extends JpaRepository<CandleData, Long> {

    List<CandleData> findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
        String symbol, String timeframe, Instant startTime, Instant endTime);

    List<CandleData> findBySymbolAndTimeframeOrderByTimestampDesc(String symbol, String timeframe);

    Optional<CandleData> findBySymbolAndTimeframeAndTimestamp(String symbol, String timeframe, Instant timestamp);

    @Query("SELECT DISTINCT c.symbol FROM CandleData c")
    List<String> findAllSymbols();

    @Query("SELECT DISTINCT c.timeframe FROM CandleData c")
    List<String> findAllTimeframes();

    @Query("SELECT MAX(c.timestamp) FROM CandleData c WHERE c.symbol = :symbol AND c.timeframe = :timeframe")
    Optional<Instant> findLatestTimestamp(String symbol, String timeframe);

    void deleteBySymbolAndTimeframeAndTimestampBefore(String symbol, String timeframe, Instant timestamp);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CandleData c WHERE c.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);
}
