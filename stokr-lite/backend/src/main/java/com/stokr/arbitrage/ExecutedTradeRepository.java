package com.stokr.arbitrage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutedTradeRepository extends JpaRepository<ExecutedTrade, Long> {

    List<ExecutedTrade> findByStatusOrderByExecutedAtDesc(String status);

    List<ExecutedTrade> findByUnderlyingAndStatusOrderByExecutedAtDesc(String underlying, String status);

    @Query("SELECT COUNT(e) FROM ExecutedTrade e WHERE e.status = 'OPEN'")
    int countOpen();

    @Query("SELECT COUNT(e) FROM ExecutedTrade e WHERE e.underlying = :underlying AND e.status = 'OPEN'")
    int countOpenByUnderlying(@Param("underlying") String underlying);

    List<ExecutedTrade> findByStatusAndUnderlyingInOrderByExecutedAtDesc(String status, List<String> underlyings);

    List<ExecutedTrade> findByUnderlyingOrderByExecutedAtDesc(String underlying);

    @Query("SELECT e FROM ExecutedTrade e WHERE e.status = 'OPEN' AND e.underlying = :underlying ORDER BY e.executedAt DESC")
    List<ExecutedTrade> findOpenByUnderlying(@Param("underlying") String underlying);
}
