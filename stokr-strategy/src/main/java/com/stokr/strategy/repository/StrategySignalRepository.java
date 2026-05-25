package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
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

    long countBySignalSourceAndDeletedFalse(SignalProvenance signalSource);

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
              and s.testTrade = false
              and (
                    (i is not null and i.deleted = false and i.userId = :userId)
                    or s.userId = :userId
                  )
            order by s.createdAt desc
            """)
    List<StrategySignalEntity> findRecentForTrader(@Param("userId") UUID userId, Pageable pageable);

    List<StrategySignalEntity> findTop30ByDeletedFalseAndTestTradeFalseOrderByCreatedAtDesc(Pageable pageable);

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

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.backtestRunId is null
              and s.testTrade = false
              and (s.outcomeStatus is null or s.outcomeStatus = 'PENDING')
              and s.createdAt >= :since
              and s.createdAt <= :before
            order by s.createdAt asc
            """)
    List<StrategySignalEntity> findPendingOutcomeTracking(
            @Param("since") Instant since,
            @Param("before") Instant before,
            Pageable pageable);

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.backtestRunId is null
              and s.testTrade = false
              and (s.outcomeStatus is null or s.outcomeStatus = 'PENDING')
            order by s.createdAt asc
            """)
    List<StrategySignalEntity> findAllPendingOutcomeTracking(Pageable pageable);

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.backtestRunId is null
              and s.testTrade = false
              and s.outcomeStatus in ('RUNNING', 'PENDING')
              and s.entryReferencePrice is not null
              and s.createdAt >= :since
            order by s.createdAt asc
            """)
    List<StrategySignalEntity> findRunningSignalsSince(@Param("since") Instant since, Pageable pageable);

    @Query("""
            select case when count(s) > 0 then true else false end
            from StrategySignalEntity s
            where s.deleted = false
              and s.testTrade = false
              and s.backtestRunId is null
              and (s.signalSource is null or s.signalSource not in (com.stokr.strategy.signals.SignalProvenance.REPLAY, com.stokr.strategy.signals.SignalProvenance.LAB))
              and s.strategyName = :strategyName
              and s.symbol = :symbol
              and s.signalType = :signalType
              and s.createdAt >= :since
            """)
    boolean existsSimilarLiveSignal(
            @Param("strategyName") String strategyName,
            @Param("symbol") String symbol,
            @Param("signalType") SignalType signalType,
            @Param("since") Instant since);

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
            WHERE deleted = FALSE AND backtest_run_id IS NULL AND is_test_trade = FALSE
              AND (signal_source IS NULL OR signal_source IN ('LIVE', 'PAPER'))
            """, nativeQuery = true)
    List<Object[]> computeStats(@Param("since") Instant since);
}
