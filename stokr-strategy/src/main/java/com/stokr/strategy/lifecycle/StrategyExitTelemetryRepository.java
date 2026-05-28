package com.stokr.strategy.lifecycle;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StrategyExitTelemetryRepository extends JpaRepository<StrategyExitTelemetry, Long> {

    List<StrategyExitTelemetry> findBySignalIdOrderByCreatedAtDesc(UUID signalId);
}
