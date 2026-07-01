package com.stokr.marketdata.tick;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TickDataRepository extends JpaRepository<TickData, Long> {

    List<TickData> findBySymbolAndExchangeTsBetweenOrderByExchangeTsAsc(
        String symbol, LocalDateTime from, LocalDateTime to);

    List<TickData> findTop100BySymbolOrderByExchangeTsDesc(String symbol);

    long countBySymbolAndExchangeTsBetween(String symbol, LocalDateTime from, LocalDateTime to);

    @Query("SELECT MAX(t.exchangeTs) FROM TickData t WHERE t.symbol = :symbol")
    LocalDateTime findLatestTickTs(@Param("symbol") String symbol);
}
