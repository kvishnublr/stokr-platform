package com.stokr.common.simulation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "simulation_runtime_control")
@Getter
@Setter
public class SimulationRuntimeControlEntity {

    @Id
    private Short id = 1;

    @Column(name = "runtime_enabled", nullable = false)
    private boolean runtimeEnabled;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "enabled_by")
    private UUID enabledBy;
}
