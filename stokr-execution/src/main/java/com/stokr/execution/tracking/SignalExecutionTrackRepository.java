package com.stokr.execution.tracking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SignalExecutionTrackRepository extends JpaRepository<SignalExecutionTrack, UUID> {

    Optional<SignalExecutionTrack> findBySignalIdAndUserId(UUID signalId, UUID userId);

    List<SignalExecutionTrack> findByStatusAndRetryCountLessThan(
            SignalExecutionTrack.SignalExecutionStatus status, Integer maxRetries);

    @Query("""
            SELECT s FROM SignalExecutionTrack s
            WHERE s.userId = :userId
            AND s.createdAt >= :since
            ORDER BY s.createdAt DESC
            """)
    Page<SignalExecutionTrack> findUserSignalsRecent(
            @Param("userId") UUID userId,
            @Param("since") Instant since,
            Pageable pageable);

    @Query("""
            SELECT s FROM SignalExecutionTrack s
            WHERE s.strategyKey = :strategyKey
            AND s.status NOT IN ('FILLED', 'CANCELLED', 'REJECTED', 'COMPLETED')
            ORDER BY s.createdAt DESC
            """)
    List<SignalExecutionTrack> findPendingByStrategy(@Param("strategyKey") String strategyKey);

    @Query("""
            SELECT s FROM SignalExecutionTrack s
            WHERE s.status IN ('VALIDATION_FAILED', 'SIZING_FAILED', 'RISK_FAILED', 'EXPOSURE_FAILED', 'BROKER_TRUTH_FAILED')
            AND s.retryCount < :maxRetries
            AND (s.lastRetryAt IS NULL OR s.lastRetryAt < :retryBefore)
            ORDER BY s.createdAt ASC
            LIMIT :limit
            """)
    List<SignalExecutionTrack> findRetryableFailures(
            @Param("maxRetries") Integer maxRetries,
            @Param("retryBefore") Instant retryBefore,
            @Param("limit") Integer limit);

    @Query("""
            SELECT COUNT(s) FROM SignalExecutionTrack s
            WHERE s.userId = :userId
            AND s.status = 'FILLED'
            AND s.createdAt >= :since
            """)
    long countFilledSignals(
            @Param("userId") UUID userId,
            @Param("since") Instant since);

    @Query("""
            SELECT COUNT(s) FROM SignalExecutionTrack s
            WHERE s.userId = :userId
            AND s.status IN ('VALIDATION_FAILED', 'SIZING_FAILED', 'RISK_FAILED', 'EXPOSURE_FAILED', 'BROKER_TRUTH_FAILED')
            AND s.createdAt >= :since
            """)
    long countFailedSignals(
            @Param("userId") UUID userId,
            @Param("since") Instant since);
}
