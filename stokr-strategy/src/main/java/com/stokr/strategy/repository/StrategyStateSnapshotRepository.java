package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyStateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StrategyStateSnapshotRepository extends JpaRepository<StrategyStateSnapshot, UUID> {

    Optional<StrategyStateSnapshot> findTopByInstance_IdAndDeletedFalseOrderBySequenceNumDesc(UUID instanceId);
}
