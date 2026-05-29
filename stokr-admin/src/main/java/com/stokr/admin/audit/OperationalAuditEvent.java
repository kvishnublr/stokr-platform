package com.stokr.admin.audit;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "operational_audit_events")
public class OperationalAuditEvent extends BaseEntity {

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "payload_json", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(name = "actor", length = 128)
    private String actor;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "is_simulation", nullable = false)
    private boolean simulation;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "simulation_run_id")
    private UUID simulationRunId;

    @Column(name = "simulation_scenario", length = 64)
    private String simulationScenario;
}
