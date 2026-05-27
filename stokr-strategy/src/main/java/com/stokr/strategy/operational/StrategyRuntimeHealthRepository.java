package com.stokr.strategy.operational;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StrategyRuntimeHealthRepository extends JpaRepository<StrategyRuntimeHealth, Long> {

    Optional<StrategyRuntimeHealth> findByStrategyNameAndSessionDate(String strategyName, LocalDate sessionDate);

    List<StrategyRuntimeHealth> findBySessionDateOrderByStrategyNameAsc(LocalDate sessionDate);
}
