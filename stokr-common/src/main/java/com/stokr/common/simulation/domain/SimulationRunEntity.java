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
@Table(name = "simulation_runs")
@Getter
@Setter
public class SimulationRunEntity {

    @Id
    private UUID id;

    @Column(name = "scenario", nullable = false, length = 64)
    private String scenario;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "report_json", columnDefinition = "text")
    private String reportJson;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;
}
