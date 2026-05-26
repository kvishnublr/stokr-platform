package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface StrategyDefinitionRepository extends JpaRepository<StrategyDefinition, UUID>,
        JpaSpecificationExecutor<StrategyDefinition> {

    Optional<StrategyDefinition> findByIdAndDeletedFalse(UUID id);

    Page<StrategyDefinition> findAllByDeletedFalse(Pageable pageable);

    Page<StrategyDefinition> findAllByDeletedFalseAndEnabledTrueAndVisibleToUsersTrue(Pageable pageable);

    Optional<StrategyDefinition> findByStrategyKeyAndDeletedFalse(String strategyKey);

    long countByDeletedFalse();

    /** Enabled strategies visible in trader catalog, ordered for settings UI. */
    java.util.List<StrategyDefinition> findAllByDeletedFalseAndEnabledTrueAndVisibleToUsersTrueOrderByStrategyKeyAsc();
}
