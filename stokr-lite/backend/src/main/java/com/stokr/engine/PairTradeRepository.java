package com.stokr.engine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PairTradeRepository extends JpaRepository<PairTrade, Long> {

    List<PairTrade> findByStatus(String status);

    List<PairTrade> findByEntryTimeBetweenOrderByEntryTimeDesc(LocalDateTime from, LocalDateTime to);

    long countByEntryTimeBetween(LocalDateTime from, LocalDateTime to);

    @Query("SELECT t FROM PairTrade t WHERE t.entryTime >= :from ORDER BY t.entryTime DESC")
    List<PairTrade> findRecentTrades(LocalDateTime from);

    @Query("SELECT t FROM PairTrade t WHERE t.status = 'CLOSED' ORDER BY t.exitTime DESC")
    List<PairTrade> findAllClosed();
}
