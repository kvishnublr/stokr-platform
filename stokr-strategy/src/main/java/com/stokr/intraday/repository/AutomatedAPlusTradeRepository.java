package com.stokr.intraday.repository;

import com.stokr.intraday.domain.AutomatedAPlusTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AutomatedAPlusTradeRepository extends JpaRepository<AutomatedAPlusTrade, Long> {

    List<AutomatedAPlusTrade> findByStatusOrderByEntryTimeDesc(String status);

    @Query("SELECT t FROM AutomatedAPlusTrade t WHERE t.status = 'ACTIVE' ORDER BY t.entryTime DESC")
    List<AutomatedAPlusTrade> findActiveTradesOrderByRecent();

    @Query("SELECT t FROM AutomatedAPlusTrade t WHERE t.status = 'ACTIVE' AND t.symbol = :symbol")
    Optional<AutomatedAPlusTrade> findActiveTradeBySymbol(@Param("symbol") String symbol);

    @Query("SELECT COUNT(t) FROM AutomatedAPlusTrade t WHERE t.status = 'ACTIVE'")
    long countActivePositions();

    @Query("SELECT t FROM AutomatedAPlusTrade t WHERE t.entryTime >= :from AND t.entryTime <= :to ORDER BY t.entryTime DESC")
    List<AutomatedAPlusTrade> findByEntryTimeRange(@Param("from") Instant from, @Param("to") Instant to);

    List<AutomatedAPlusTrade> findBySymbolOrderByEntryTimeDesc(String symbol);
}
