package com.stokr.oms.repository;

import com.stokr.oms.domain.PortfolioDailySummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioDailySummaryRepository extends JpaRepository<PortfolioDailySummary, UUID> {

    Optional<PortfolioDailySummary> findByUserIdAndBusinessDateAndDeletedFalse(UUID userId, LocalDate businessDate);

    List<PortfolioDailySummary> findTop366ByUserIdAndDeletedFalseOrderByBusinessDateDesc(UUID userId);
}
