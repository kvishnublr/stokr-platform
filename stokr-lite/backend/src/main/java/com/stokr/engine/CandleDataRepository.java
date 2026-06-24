package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleDataRepository extends JpaRepository<CandleData, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM CandleData c WHERE c.timestamp = :ts AND c.timeframe = '1min'")
    int delete1minByTimestamp(LocalDateTime ts);

    List<CandleData> findBySymbolAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
        String symbol, String timeframe, LocalDateTime startTime, LocalDateTime endTime);

    List<CandleData> findBySymbolAndTimeframeOrderByTimestampDesc(String symbol, String timeframe);

    Optional<CandleData> findBySymbolAndTimeframeAndTimestamp(String symbol, String timeframe, LocalDateTime timestamp);

    @Query("SELECT DISTINCT c.symbol FROM CandleData c")
    List<String> findAllSymbols();

    @Query("SELECT DISTINCT c.timeframe FROM CandleData c")
    List<String> findAllTimeframes();

    @Query("SELECT MAX(c.timestamp) FROM CandleData c WHERE c.symbol = :symbol AND c.timeframe = :timeframe")
    Optional<LocalDateTime> findLatestTimestamp(String symbol, String timeframe);

    @Modifying
    @Transactional
    @Query("DELETE FROM CandleData c WHERE c.timestamp < :cutoff")
    int deleteByTimestampBefore(LocalDateTime cutoff);
}
