package com.stokr.arbitrage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ArbSignalRepository extends JpaRepository<Signal, Long> {

    List<Signal> findByStatusOrderByScanTimeDesc(String status);

    List<Signal> findByScanTimeBetweenOrderByScanTimeDesc(LocalDateTime start, LocalDateTime end);

    List<Signal> findByUnderlyingAndScanTimeBetweenOrderByScanTimeDesc(String underlying, LocalDateTime start, LocalDateTime end);

    Page<Signal> findByScanTimeBetweenOrderByScanTimeDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    @Query("SELECT COUNT(s) FROM Signal s WHERE s.scanTime BETWEEN :start AND :end")
    long countByScanTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(s) FROM Signal s WHERE s.scanTime BETWEEN :start AND :end AND s.status = :status")
    long countByScanTimeBetweenAndStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("status") String status);

    @Query("SELECT s.underlying, COUNT(s) FROM Signal s WHERE s.scanTime BETWEEN :start AND :end GROUP BY s.underlying")
    List<Object[]> countByUnderlyingBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
