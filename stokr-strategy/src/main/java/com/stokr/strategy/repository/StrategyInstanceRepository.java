package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategyInstance;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyInstanceRepository extends JpaRepository<StrategyInstance, UUID> {

    long countByUserIdAndEnabledTrueAndDeletedFalse(UUID userId);

    Optional<StrategyInstance> findByUserIdAndDefinition_IdAndDeletedFalse(UUID userId, UUID definitionId);

    Optional<StrategyInstance> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);

    Page<StrategyInstance> findPageByUserIdAndDeletedFalse(UUID userId, Pageable pageable);

    List<StrategyInstance> findAllByUserIdAndDeletedFalse(UUID userId);

    @Query("select si from StrategyInstance si join fetch si.definition where si.userId = :userId and si.deleted = false")
    List<StrategyInstance> findAllForUserWithDefinition(@Param("userId") UUID userId);

    long countByRuntimeStateAndDeletedFalse(String runtimeState);

    @Query("""
            select si from StrategyInstance si
            where si.runtimeState = 'RUNNING' and si.deleted = false
            and (
              (si.heartbeatAt is not null and si.heartbeatAt < :cutoff)
              or (si.heartbeatAt is null and si.startedAt is not null and si.startedAt < :cutoff)
            )
            """)
    List<StrategyInstance> findRunningWithStaleHeartbeat(@Param("cutoff") Instant cutoff);

    List<StrategyInstance> findAllByRuntimeStateAndDeletedFalse(String runtimeState);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select si from StrategyInstance si where si.id = :id and si.deleted = false")
    Optional<StrategyInstance> lockById(@Param("id") UUID id);

    @Query("select si from StrategyInstance si join fetch si.definition where si.enabled = true and si.deleted = false and si.runtimeState != 'RUNNING'")
    List<StrategyInstance> findAllEnabledNonRunning();

    @Query("select si from StrategyInstance si join fetch si.definition d where d.strategyKey = :strategyKey and si.runtimeState = 'RUNNING' and si.deleted = false")
    List<StrategyInstance> findAllRunningByStrategyKey(@Param("strategyKey") String strategyKey);

    @Query("""
            select case when count(si) > 0 then true else false end from StrategyInstance si
            join si.definition d
            where si.userId = :userId and si.deleted = false
            and d.strategyKey = :strategyKey
            and upper(si.executionMode) in ('LIVE', 'BOTH')
            and si.runtimeState = 'RUNNING'
            and si.heartbeatAt is not null and si.heartbeatAt >= :cutoff
            """)
    boolean existsLiveHealthyRuntime(
            @Param("userId") UUID userId,
            @Param("strategyKey") String strategyKey,
            @Param("cutoff") Instant cutoff
    );

    /**
     * Atomic heartbeat touch for all RUNNING instances. Bypasses entity loading so the
     * refresh scheduler can never lose to a concurrent writer via @Version conflicts
     * (ObjectOptimisticLockingFailureException) — a stale heartbeat blocks LIVE dispatch
     * through existsLiveHealthyRuntime, so this must always land.
     */
    @Transactional
    @Modifying
    @Query("""
            update StrategyInstance si set si.heartbeatAt = :now, si.updatedAt = :now
            where si.runtimeState = 'RUNNING' and si.deleted = false
            """)
    int touchHeartbeatsForRunning(@Param("now") Instant now);

    /** Atomic stale-stop; returns rows actually transitioned (still RUNNING at write time). */
    @Transactional
    @Modifying
    @Query("""
            update StrategyInstance si
            set si.orchestrationState = 'ERROR', si.runtimeState = 'STOPPED', si.updatedAt = :now
            where si.id in :ids and si.runtimeState = 'RUNNING' and si.deleted = false
            """)
    int markStaleStopped(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}
