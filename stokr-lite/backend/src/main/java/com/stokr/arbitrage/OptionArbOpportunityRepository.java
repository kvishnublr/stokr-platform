package com.stokr.arbitrage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OptionArbOpportunityRepository extends JpaRepository<OptionArbOpportunity, Long> {

    Page<OptionArbOpportunity> findAllByOrderByScanTimeDesc(Pageable pageable);

    List<OptionArbOpportunity> findByStatusOrderByScanTimeDesc(String status);

    List<OptionArbOpportunity> findByStatus(String status);

    @Query("SELECT o FROM OptionArbOpportunity o WHERE o.status = :status AND o.scanTime >= :since ORDER BY o.scanTime DESC")
    List<OptionArbOpportunity> findRecentByStatus(@Param("status") String status, @Param("since") LocalDateTime since);

    @Query(value = "SELECT * FROM option_arb_opportunities WHERE status = :status AND scan_time >= :since ORDER BY scan_time DESC LIMIT :maxRows", nativeQuery = true)
    List<OptionArbOpportunity> findRecentByStatusLimited(@Param("status") String status, @Param("since") LocalDateTime since, @Param("maxRows") int maxRows);

    @Query("SELECT o FROM OptionArbOpportunity o WHERE o.status = :status AND o.scanTime >= :start AND o.scanTime < :end ORDER BY o.scanTime DESC")
    List<OptionArbOpportunity> findByStatusOrderByScanTimeBetween(@Param("status") String status, @Param("start") java.time.LocalDateTime start, @Param("end") java.time.LocalDateTime end);

    @Query("SELECT o FROM OptionArbOpportunity o WHERE o.scanTime >= :start AND o.scanTime < :end ORDER BY o.scanTime DESC")
    List<OptionArbOpportunity> findByScanTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o WHERE o.scanTime >= :start AND o.scanTime < :end")
    long countByScanTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o WHERE o.scanTime >= :start AND o.scanTime < :end AND o.status = :status")
    long countByScanTimeBetweenAndStatus(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("status") String status);

    @Query("SELECT COALESCE(SUM(o.pnlAfterCosts), 0) FROM OptionArbOpportunity o WHERE o.scanTime >= :start AND o.scanTime < :end AND o.status = 'CLOSED'")
    java.math.BigDecimal sumPnlByScanTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(o.edgeAfterCosts), 0) FROM OptionArbOpportunity o WHERE o.scanTime >= :start AND o.scanTime < :end")
    java.math.BigDecimal sumEdgeByScanTimeBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT DISTINCT o.scanTime FROM OptionArbOpportunity o WHERE o.scanTime >= :since ORDER BY o.scanTime DESC")
    List<LocalDateTime> findDistinctScanTimesSince(@Param("since") LocalDateTime since);

    Page<OptionArbOpportunity> findByScanTimeBeforeOrderByScanTimeDesc(LocalDateTime before, Pageable pageable);

    Page<OptionArbOpportunity> findByScanTimeBetweenOrderByScanTimeDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<OptionArbOpportunity> findByScanTimeBetweenAndUnderlyingOrderByScanTimeDesc(LocalDateTime start, LocalDateTime end, String underlying);

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o")
    long countAll();

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o WHERE o.status = :status")
    long countAllByStatus(@Param("status") String status);

    @Query("SELECT COALESCE(SUM(o.edgeAfterCosts), 0) FROM OptionArbOpportunity o")
    java.math.BigDecimal sumEdgeAll();

    @Query("SELECT COALESCE(SUM(o.pnlAfterCosts), 0) FROM OptionArbOpportunity o WHERE o.pnlAfterCosts IS NOT NULL")
    java.math.BigDecimal sumPnlAll();

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o WHERE o.pnlAfterCosts IS NOT NULL AND o.pnlAfterCosts > 0")
    long countWinsAll();

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o WHERE o.pnlAfterCosts IS NOT NULL")
    long countWithPnlAll();

    List<OptionArbOpportunity> findByStrategyTypeOrderByScanTimeDesc(String strategyType);

    @Query("SELECT COALESCE(SUM(o.edgeAfterCosts), 0) FROM OptionArbOpportunity o WHERE o.strategyType = :strategyType")
    java.math.BigDecimal sumEdgeByStrategy(@Param("strategyType") String strategyType);

    @Query("SELECT COUNT(o) FROM OptionArbOpportunity o WHERE o.strategyType = :strategyType")
    long countByStrategy(@Param("strategyType") String strategyType);
}
