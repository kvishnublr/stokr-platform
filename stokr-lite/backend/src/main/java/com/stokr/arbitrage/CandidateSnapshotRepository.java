package com.stokr.arbitrage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CandidateSnapshotRepository extends JpaRepository<CandidateSnapshot, Long> {

    @Query("SELECT s FROM CandidateSnapshot s WHERE s.strategyType = :strategyType " +
           "AND s.snapshotTime BETWEEN :start AND :end " +
           "AND (:underlying IS NULL OR s.underlying = :underlying) " +
           "ORDER BY s.snapshotTime DESC")
    List<CandidateSnapshot> findInRange(@Param("strategyType") String strategyType,
                                         @Param("underlying") String underlying,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);
}
