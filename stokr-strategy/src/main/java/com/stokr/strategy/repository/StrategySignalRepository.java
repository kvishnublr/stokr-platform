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

public interface StrategySignalRepository extends JpaRepository<StrategySignalEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<StrategySignalEntity> {

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

    @Query(value = """
            SELECT
                COUNT(*) FILTER (WHERE created_at >= :since)::bigint                                         AS total_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND signal_type = 'BUY')::bigint                 AS buy_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND signal_type = 'SELL')::bigint                AS sell_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND pipeline = 'LIVE')::bigint                   AS live_today,
                COUNT(*) FILTER (WHERE created_at >= :since AND pipeline = 'PAPER')::bigint                  AS paper_today,
                AVG(confidence_score) FILTER (WHERE created_at >= :since)                                     AS avg_confidence,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status = 'TARGET_HIT')::bigint        AS target_hit,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status = 'STOPLOSS_HIT')::bigint      AS sl_hit,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status = 'RUNNING')::bigint           AS running_count,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status = 'EXPIRED')::bigint           AS expired_count,
                COUNT(*)::bigint                                                                               AS total_all_time
            FROM strategy_signals
            WHERE deleted = FALSE AND backtest_run_id IS NULL
            """, nativeQuery = true)
    List<Object[]> computeStats(@Param("since") Instant since);
}
