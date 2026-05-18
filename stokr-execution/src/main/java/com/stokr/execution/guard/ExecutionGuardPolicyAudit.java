package com.stokr.execution.guard;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "execution_guard_policy_audit")
public class ExecutionGuardPolicyAudit extends BaseEntity {
    @Column(name = "actor_user_id")
    private UUID actorUserId;
    @Column(name = "action", nullable = false, length = 32)
    private String action;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 32)
    private ExecutionGuardScopeType scopeType;
    @Column(name = "scope_key", length = 128)
    private String scopeKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "guard_mode", length = 32)
    private ExecutionGuardMode guardMode;
    @Column(name = "before_payload", columnDefinition = "jsonb")
    private String beforePayload;
    @Column(name = "after_payload", columnDefinition = "jsonb")
    private String afterPayload;
    @Column(name = "notes", length = 512)
    private String notes;
    @Column(name = "revision", nullable = false)
    private Long revision = 1L;
}

