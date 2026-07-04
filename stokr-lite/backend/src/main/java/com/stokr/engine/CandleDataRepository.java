package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleDataRepository extends JpaRepository<CandleData, Long> {

    @Modifying(clearAutomatically = true)
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

    long countBySymbolAndTimeframeAndTimestampBetween(String symbol, String timeframe, LocalDateTime start, LocalDateTime end);

    @Modifying
    @Transactional
    @Query("DELETE FROM CandleData c WHERE c.timestamp < :cutoff AND c.timeframe = '1min'")
    int deleteByTimestampBefore(LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM CandleData c WHERE c.timestamp < :cutoff AND c.timeframe = :timeframe")
    int deleteByTimestampBeforeAndTimeframe(LocalDateTime cutoff, String timeframe);

    @Query("SELECT DISTINCT CAST(c.timestamp AS java.time.LocalDate) FROM CandleData c " +
           "WHERE c.symbol = :symbol AND c.timeframe = :timeframe " +
           "AND c.timestamp BETWEEN :start AND :end ORDER BY CAST(c.timestamp AS java.time.LocalDate)")
    List<LocalDate> findDistinctTradingDaysBetween(String symbol, String timeframe,
                                                     LocalDateTime start, LocalDateTime end);

    @Query("SELECT c FROM CandleData c WHERE c.symbol = :symbol AND c.timeframe = :timeframe " +
           "AND c.timestamp BETWEEN :start AND :end ORDER BY c.timestamp ASC")
    List<CandleData> findCandlesBetween(String symbol, String timeframe,
                                          LocalDateTime start, LocalDateTime end);
}
