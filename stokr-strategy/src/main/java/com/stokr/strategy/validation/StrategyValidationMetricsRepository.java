package com.stokr.strategy.validation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyValidationMetricsRepository extends JpaRepository<StrategyValidationMetrics, UUID> {

    Optional<StrategyValidationMetrics> findByStrategyNameAndSessionDate(String strategyName, LocalDate sessionDate);

    List<StrategyValidationMetrics> findByStrategyNameOrderBySessionDateDesc(String strategyName);
}
