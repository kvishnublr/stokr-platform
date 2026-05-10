package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategySignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategySignalRepository extends JpaRepository<StrategySignalEntity, UUID> {

    List<StrategySignalEntity> findTop200ByDeletedFalseOrderByCreatedAtDesc();

    long countByBacktestRunId(UUID backtestRunId);

    @Query("select count(s) from StrategySignalEntity s where s.instance.id = :instanceId and s.deleted = false")
    long countByInstanceId(@Param("instanceId") UUID instanceId);

    Optional<StrategySignalEntity> findFirstByInstance_IdAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId);
}
