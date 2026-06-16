package com.stokr.strategy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {

    List<Strategy> findByEnabledTrue();

    Optional<Strategy> findByStrategyType(String strategyType);
}
