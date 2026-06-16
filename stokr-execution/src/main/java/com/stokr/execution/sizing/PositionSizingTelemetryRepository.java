package com.stokr.execution.sizing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PositionSizingTelemetryRepository extends JpaRepository<PositionSizingTelemetry, UUID> {
}
