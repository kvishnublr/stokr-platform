package com.stokr.execution.guard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionGuardPolicyRegistryRepository extends JpaRepository<ExecutionGuardPolicyRegistry, UUID> {
    Optional<ExecutionGuardPolicyRegistry> findByRegistryKeyIgnoreCaseAndDeletedFalse(String registryKey);
}

