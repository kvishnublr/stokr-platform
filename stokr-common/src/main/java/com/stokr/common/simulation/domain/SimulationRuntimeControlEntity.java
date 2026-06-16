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

    // Explicit methods to ensure compilation works
    public boolean isRuntimeEnabled() {
        return runtimeEnabled;
    }

    public void setRuntimeEnabled(boolean runtimeEnabled) {
        this.runtimeEnabled = runtimeEnabled;
    }

    public Short getId() {
        return id;
    }

    public void setId(Short id) {
        this.id = id;
    }

    public Instant getEnabledAt() {
        return enabledAt;
    }

    public void setEnabledAt(Instant enabledAt) {
        this.enabledAt = enabledAt;
    }

    public UUID getEnabledBy() {
        return enabledBy;
    }

    public void setEnabledBy(UUID enabledBy) {
        this.enabledBy = enabledBy;
    }
}
