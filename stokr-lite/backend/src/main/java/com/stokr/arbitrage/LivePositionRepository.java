package com.stokr.arbitrage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LivePositionRepository extends JpaRepository<LivePosition, Long> {

    List<LivePosition> findByStatusOrderByEnteredAtDesc(String status);

    List<LivePosition> findByUserIdAndStatusOrderByEnteredAtDesc(Long userId, String status);

    List<LivePosition> findByUserIdOrderByEnteredAtDesc(Long userId);

    @Query("SELECT p FROM LivePosition p WHERE p.status = 'OPEN' ORDER BY p.enteredAt DESC")
    List<LivePosition> findAllOpen();

    @Query("SELECT COUNT(p) FROM LivePosition p WHERE p.status = 'OPEN' AND p.underlying = :underlying")
    long countOpenByUnderlying(@Param("underlying") String underlying);

    @Query("SELECT COUNT(p) FROM LivePosition p WHERE p.status = 'OPEN'")
    long countAllOpen();

    @Query("SELECT p FROM LivePosition p WHERE p.status IN ('OPEN','ENTERED','PARTIAL','EXECUTING') ORDER BY p.enteredAt DESC")
    List<LivePosition> findAllActive();

    List<LivePosition> findByOpportunityIdOrderByEnteredAtDesc(Long opportunityId);

    @Query("""
        SELECT p FROM LivePosition p
        WHERE UPPER(COALESCE(p.strategyType,'')) LIKE CONCAT('%', UPPER(:needle), '%')
        ORDER BY COALESCE(p.enteredAt, p.createdAt) DESC
        """)
    List<LivePosition> findByStrategyNeedle(@Param("needle") String needle);

    @Query("""
        SELECT p FROM LivePosition p
        WHERE p.underlying = :underlying AND p.strike = :strike
          AND UPPER(COALESCE(p.action,'')) = UPPER(:action)
          AND UPPER(COALESCE(p.strategyType,'')) LIKE CONCAT('%', UPPER(:needle), '%')
          AND p.status IN ('OPEN','ENTERED','PARTIAL')
        ORDER BY p.enteredAt DESC
        """)
    List<LivePosition> findActiveByFingerprint(
            @Param("underlying") String underlying,
            @Param("strike") Integer strike,
            @Param("action") String action,
            @Param("needle") String needle);
}
