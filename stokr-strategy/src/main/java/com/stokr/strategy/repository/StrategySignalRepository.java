package com.stokr.strategy.repository;

import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.signals.SignalProvenance;
import com.stokr.strategy.signals.SignalType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategySignalRepository extends JpaRepository<StrategySignalEntity, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<StrategySignalEntity> {

    long countByDeletedFalse();

    long countBySignalSourceAndDeletedFalse(SignalProvenance signalSource);

    long countByCreatedAtAfterAndDeletedFalse(Instant since);

    List<StrategySignalEntity> findByCreatedAtAfterAndDeletedFalseOrderByCreatedAtAsc(Instant since);

    List<StrategySignalEntity> findTop200ByDeletedFalseOrderByCreatedAtDesc();

    List<StrategySignalEntity> findBySimulationRunIdAndDeletedFalseOrderByCreatedAtDesc(UUID simulationRunId);

    long countBySimulationRunIdAndDeletedFalse(UUID simulationRunId);

    long countBySimulationTrueAndDeletedFalse();

    long countByBacktestRunId(UUID backtestRunId);

    @Query("select count(s) from StrategySignalEntity s where s.instance.id = :instanceId and s.deleted = false")
    long countByInstanceId(@Param("instanceId") UUID instanceId);

    Optional<StrategySignalEntity> findFirstByInstance_IdAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId);

    @Query("""
            select distinct s from StrategySignalEntity s
            left join s.instance i
            where s.deleted = false
              and s.testTrade = false
              and s.backtestRunId is null
              and (
                    (i is not null and i.deleted = false and i.userId = :userId)
                    or s.userId = :userId
                    or exists (
                        select 1 from StrategyInstance si
                        join si.definition d
                        where si.userId = :userId
                          and si.deleted = false
                          and si.enabled = true
                          and upper(d.strategyKey) = upper(s.strategyName)
                    )
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
              and s.testTrade = false
              and s.backtestRunId is null
              and (s.signalSource is null
                   or s.signalSource in (com.stokr.strategy.signals.SignalProvenance.LIVE,
                                         com.stokr.strategy.signals.SignalProvenance.PAPER))
            order by s.createdAt desc
            """)
    List<StrategySignalEntity> findLatestProductionSignals(Pageable pageable);

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
              and s.createdAt >= :since
            order by s.createdAt asc
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<StrategySignalEntity> findRunningSignalsSince(@Param("since") Instant since, Pageable pageable);

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.backtestRunId is null
              and s.testTrade = false
              and s.outcomeStatus in ('RUNNING', 'PENDING')
            order by s.createdAt desc
            """)
    List<StrategySignalEntity> findActiveRunningSignals();

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

    @Query("""
            select case when count(s) > 0 then true else false end
            from StrategySignalEntity s
            where s.deleted = false
              and s.testTrade = false
              and s.backtestRunId is null
              and (s.signalSource is null or s.signalSource not in (com.stokr.strategy.signals.SignalProvenance.REPLAY, com.stokr.strategy.signals.SignalProvenance.LAB))
              and s.strategyName = :strategyName
              and s.symbol = :symbol
              and s.outcomeTime >= :since
            """)
    boolean existsRecentlyExitedSignal(
            @Param("strategyName") String strategyName,
            @Param("symbol") String symbol,
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
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS sl_hit,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status = 'RUNNING')::bigint           AS running_count,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status IN ('EXPIRED', 'TIME_EXIT'))::bigint AS expired_count,
                COUNT(*) FILTER (WHERE created_at >= :since AND outcome_status IN ('PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'FEED_PROTECTION', 'BREAKEVEN_EXIT'))::bigint AS protected_count,
                COUNT(*) FILTER (WHERE created_at >= :since AND (outcome_status IS NULL OR outcome_status = 'PENDING'))::bigint AS pending_count,
                COUNT(*)::bigint                                                                               AS total_all_time
            FROM strategy_signals
            WHERE deleted = FALSE AND backtest_run_id IS NULL AND is_test_trade = FALSE
              AND (signal_source IS NULL OR signal_source IN ('LIVE', 'PAPER'))
            """, nativeQuery = true)
    List<Object[]> computeStats(@Param("since") Instant since);

    @Query("""
            select s.strategyName, count(s), max(s.createdAt)
            from StrategySignalEntity s
            where s.deleted = false
              and s.testTrade = false
              and s.backtestRunId is null
              and s.createdAt >= :since
              and (s.signalSource is null
                   or s.signalSource in (com.stokr.strategy.signals.SignalProvenance.LIVE,
                                         com.stokr.strategy.signals.SignalProvenance.PAPER))
            group by s.strategyName
            """)
    List<Object[]> countLiveSignalsSinceGroupedByStrategyName(@Param("since") Instant since);

    @Query("""
            select count(s)
            from StrategySignalEntity s
            where s.deleted = false
              and s.testTrade = false
              and s.backtestRunId is null
              and s.strategyName = :strategyName
              and s.createdAt >= :since
              and (s.signalSource is null
                   or s.signalSource in (com.stokr.strategy.signals.SignalProvenance.LIVE,
                                         com.stokr.strategy.signals.SignalProvenance.PAPER))
            """)
    long countProductionSignalsForStrategySince(
            @Param("strategyName") String strategyName,
            @Param("since") Instant since);

    @Query("""
            select count(s)
            from StrategySignalEntity s
            where s.deleted = false
              and s.testTrade = false
              and s.backtestRunId is null
              and upper(s.strategyName) = upper(:strategyName)
              and upper(s.symbol) = upper(:symbol)
              and s.createdAt >= :since
              and (s.signalSource is null
                   or s.signalSource in (com.stokr.strategy.signals.SignalProvenance.LIVE,
                                         com.stokr.strategy.signals.SignalProvenance.PAPER))
            """)
    long countProductionSignalsForStrategyAndSymbolSince(
            @Param("strategyName") String strategyName,
            @Param("symbol") String symbol,
            @Param("since") Instant since);

    @Query("""
            select count(s) from StrategySignalEntity s
            where s.deleted = false
              and s.backtestRunId is null
              and s.createdAt >= :from
              and s.createdAt < :toExclusive
              and (:strategyName is null or upper(s.strategyName) = upper(:strategyName))
              and (:includeReplayAndLab = true
                   or s.signalSource is null
                   or s.signalSource in (com.stokr.strategy.signals.SignalProvenance.LIVE,
                                         com.stokr.strategy.signals.SignalProvenance.PAPER))
            """)
    long countForCleanup(
            @Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("strategyName") String strategyName,
            @Param("includeReplayAndLab") boolean includeReplayAndLab);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StrategySignalEntity s
            set s.deleted = true, s.updatedAt = current_timestamp
            where s.deleted = false
              and s.backtestRunId is null
              and s.createdAt >= :from
              and s.createdAt < :toExclusive
              and (:strategyName is null or upper(s.strategyName) = upper(:strategyName))
              and (:includeReplayAndLab = true
                   or s.signalSource is null
                   or s.signalSource in (com.stokr.strategy.signals.SignalProvenance.LIVE,
                                         com.stokr.strategy.signals.SignalProvenance.PAPER))
            """)
    int softDeleteForCleanup(
            @Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("strategyName") String strategyName,
            @Param("includeReplayAndLab") boolean includeReplayAndLab);

    @Query(value = """
            SELECT
                strategy_name,
                COUNT(*)::bigint                                                         AS total,
                COUNT(*) FILTER (WHERE signal_type = 'BUY')::bigint                      AS buy_count,
                COUNT(*) FILTER (WHERE signal_type = 'SELL')::bigint                     AS sell_count,
                COUNT(*) FILTER (WHERE outcome_status = 'TARGET_HIT')::bigint            AS target_hit,
                COUNT(*) FILTER (WHERE outcome_status IN ('STOPLOSS_HIT', 'SL_HIT'))::bigint AS sl_hit,
                COUNT(*) FILTER (WHERE outcome_status = 'RUNNING')::bigint                 AS running_count,
                COUNT(*) FILTER (WHERE outcome_status IN ('EXPIRED', 'TIME_EXIT'))::bigint AS expired_count,
                COUNT(*) FILTER (WHERE outcome_status IN ('PRESSURE_EXIT', 'LIQUIDITY_PROTECTION', 'FEED_PROTECTION', 'BREAKEVEN_EXIT'))::bigint AS protected_count,
                COUNT(*) FILTER (WHERE outcome_status IS NULL OR outcome_status = 'PENDING')::bigint AS pending_count
            FROM strategy_signals
            WHERE deleted = FALSE
              AND backtest_run_id IS NULL
              AND is_test_trade = FALSE
              AND created_at >= :from
              AND created_at < :toExclusive
              AND (:strategyName IS NULL OR upper(strategy_name) = upper(CAST(:strategyName AS text)))
              AND (
                    CAST(:includeReplayAndLab AS boolean) = TRUE
                    OR signal_source IS NULL
                    OR signal_source IN ('LIVE', 'PAPER')
                  )
            GROUP BY strategy_name
            ORDER BY total DESC
            """, nativeQuery = true)
    List<Object[]> computeStatsByStrategy(
            @Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("strategyName") String strategyName,
            @Param("includeReplayAndLab") boolean includeReplayAndLab);

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.outcomeTime >= :since
              and s.outcomeStatus in :outcomes
              and s.outcomeExitDisposition is null
              and (s.testTrade = false or s.testTrade is null)
              and (s.signalSource is null or s.signalSource not in :excludedSources)
            order by s.outcomeTime asc
            """)
    List<StrategySignalEntity> findTerminalOutcomesSince(
            @Param("since") Instant since,
            @Param("outcomes") Collection<String> outcomes,
            @Param("excludedSources") Collection<SignalProvenance> excludedSources);

    /** First write wins; direct update avoids optimistic-lock conflicts with the outcome tracker. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
            update StrategySignalEntity s set s.outcomeExitDisposition = :disposition
            where s.id = :id and s.outcomeExitDisposition is null
            """)
    int settleOutcomeExitDisposition(@Param("id") UUID id, @Param("disposition") String disposition);

    // ===== Position Sweeper queries =====

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.outcomeStatus in ('RUNNING', 'PENDING')
              and s.testTrade = false
              and s.createdAt < :maxCreatedAt
            order by s.createdAt asc
            """)
    List<StrategySignalEntity> findRunningSignalsCreatedBefore(@Param("maxCreatedAt") Instant maxCreatedAt);

    @Query("""
            select s from StrategySignalEntity s
            where s.deleted = false
              and s.testTrade = false
              and (s.outcomeStatus is null or s.outcomeStatus not in :terminalOutcomes)
              and s.createdAt < :maxCreatedAt
            order by s.createdAt asc
            """)
    List<StrategySignalEntity> findNonTerminalSignalsCreatedBefore(
            @Param("maxCreatedAt") Instant maxCreatedAt,
            @Param("terminalOutcomes") Collection<String> terminalOutcomes);
}
