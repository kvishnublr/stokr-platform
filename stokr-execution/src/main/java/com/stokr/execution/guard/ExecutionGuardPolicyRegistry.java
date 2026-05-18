package com.stokr.execution.guard;

import com.stokr.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "execution_guard_policy_registry")
public class ExecutionGuardPolicyRegistry extends BaseEntity {
    @Column(name = "registry_key", nullable = false, length = 64)
    private String registryKey;
    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;
    @Column(name = "active", nullable = false)
    private boolean active = true;
    @Column(name = "revision", nullable = false)
    private Long revision = 1L;
    @Column(name = "notes", length = 512)
    private String notes;
}

