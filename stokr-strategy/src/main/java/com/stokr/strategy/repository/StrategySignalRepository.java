package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategySignalEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategySignalRepository extends JpaRepository<StrategySignalEntity, UUID> {

    long countByDeletedFalse();

    long countByCreatedAtAfterAndDeletedFalse(Instant since);

    List<StrategySignalEntity> findTop200ByDeletedFalseOrderByCreatedAtDesc();

    long countByBacktestRunId(UUID backtestRunId);

    @Query("select count(s) from StrategySignalEntity s where s.instance.id = :instanceId and s.deleted = false")
    long countByInstanceId(@Param("instanceId") UUID instanceId);

    Optional<StrategySignalEntity> findFirstByInstance_IdAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId);

    @Query("""
            select s from StrategySignalEntity s
            left join s.instance i
            where s.deleted = false
              and (
                    (i is not null and i.deleted = false and i.userId = :userId)
                    or s.userId = :userId
                  )
            order by s.createdAt desc
            """)
    List<StrategySignalEntity> findRecentForTrader(@Param("userId") UUID userId, Pageable pageable);

    List<StrategySignalEntity> findTop30ByDeletedFalseOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select count(s) from StrategySignalEntity s
            where s.deleted = false and s.createdAt >= :since and s.backtestRunId is null
            """)
    long countByCreatedAtAfterAndDeletedFalseAndBacktestRunIdNull(@Param("since") Instant since);

    @Query("""
            select count(s) from StrategySignalEntity s
            where s.deleted = false and s.createdAt >= :since and s.backtestRunId is not null
            """)
    long countByCreatedAtAfterAndDeletedFalseAndBacktestRunIdNotNull(@Param("since") Instant since);

    Optional<StrategySignalEntity> findFirstByDeletedFalseOrderByCreatedAtDesc();
}
