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
}
