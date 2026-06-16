package com.stokr.common.simulation.repository;

import com.stokr.common.simulation.domain.SimulationRuntimeControlEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationRuntimeControlRepository extends JpaRepository<SimulationRuntimeControlEntity, Short> {
}
