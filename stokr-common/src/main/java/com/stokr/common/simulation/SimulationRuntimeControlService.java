package com.stokr.common.simulation;

import com.stokr.common.simulation.domain.SimulationRuntimeControlEntity;
import com.stokr.common.simulation.repository.SimulationRuntimeControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Runtime simulation toggle — the only way to activate simulation after startup.
 * YAML {@code stokr.simulation-mode.enabled} must remain {@code false}; it does not activate simulation.
 */
@Service
@RequiredArgsConstructor
public class SimulationRuntimeControlService {

    private final SimulationRuntimeControlRepository repository;

    @Transactional(readOnly = true)
    public boolean isRuntimeEnabled() {
        return repository.findById((short) 1)
                .map(SimulationRuntimeControlEntity::isRuntimeEnabled)
                .orElse(false);
    }

    @Transactional
    public void enable(UUID adminUserId) {
        SimulationRuntimeControlEntity row = repository.findById((short) 1)
                .orElseGet(this::newControlRow);
        row.setRuntimeEnabled(true);
        row.setEnabledAt(Instant.now());
        row.setEnabledBy(adminUserId);
        repository.save(row);
    }

    @Transactional
    public void disable() {
        SimulationRuntimeControlEntity row = repository.findById((short) 1)
                .orElseGet(this::newControlRow);
        row.setRuntimeEnabled(false);
        row.setEnabledAt(null);
        row.setEnabledBy(null);
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public SimulationRuntimeStatus status() {
        SimulationRuntimeControlEntity row = repository.findById((short) 1).orElseGet(this::newControlRow);
        return new SimulationRuntimeStatus(
                row.isRuntimeEnabled(),
                row.getEnabledAt(),
                row.getEnabledBy()
        );
    }

    private SimulationRuntimeControlEntity newControlRow() {
        SimulationRuntimeControlEntity e = new SimulationRuntimeControlEntity();
        e.setId((short) 1);
        e.setRuntimeEnabled(false);
        return e;
    }

    public record SimulationRuntimeStatus(boolean enabled, Instant enabledAt, UUID enabledBy) {
    }
}
