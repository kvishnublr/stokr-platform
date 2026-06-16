package com.stokr.trading.repository;

import com.stokr.trading.domain.StrategyInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StrategyInstanceRepository extends JpaRepository<StrategyInstance, UUID> {

    Optional<StrategyInstance> findByIdAndDeletedFalse(UUID id);

    List<StrategyInstance> findByUserIdAndDeletedFalse(UUID userId);

    List<StrategyInstance> findByStrategyIdAndDeletedFalse(UUID strategyId);

    List<StrategyInstance> findByOrganizationIdAndDeletedFalse(UUID organizationId);

    List<StrategyInstance> findByUserIdAndStatusAndDeletedFalse(UUID userId, String status);

    List<StrategyInstance> findByUserIdAndEnabledTrueAndDeletedFalse(UUID userId);

    @Query("SELECT COUNT(si) FROM StrategyInstance si WHERE si.userId = :userId AND si.deleted = false")
    int countByUserId(UUID userId);

    @Query("SELECT COUNT(si) FROM StrategyInstance si WHERE si.organizationId = :orgId AND si.deleted = false")
    int countByOrganizationId(UUID organizationId);

    List<StrategyInstance> findByBrokerAccountIdAndDeletedFalse(UUID brokerAccountId);

    Optional<StrategyInstance> findByUserIdAndSymbolAndDeletedFalse(UUID userId, String symbol);

    List<StrategyInstance> findByStatusAndDeletedFalse(String status);

    @Query("SELECT si FROM StrategyInstance si WHERE si.userId = :userId AND si.status = 'RUNNING' AND si.deleted = false")
    List<StrategyInstance> findRunningByUserId(UUID userId);
}
