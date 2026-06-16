package com.stokr.trading.repository;

import com.stokr.trading.domain.Signal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SignalRepository extends JpaRepository<Signal, UUID> {

    List<Signal> findByInstanceIdAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId);

    Page<Signal> findByInstanceIdAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId, Pageable pageable);

    List<Signal> findByInstanceIdAndStatusAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId, String status);

    List<Signal> findByUserIdAndDeletedFalseOrderByCreatedAtDesc(UUID userId);

    List<Signal> findByInstanceIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID instanceId, Instant start, Instant end);

    List<Signal> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since);

    @Query("SELECT COUNT(s) FROM Signal s WHERE s.instanceId = :instanceId AND s.status = :status AND s.deleted = false")
    int countByInstanceIdAndStatus(UUID instanceId, String status);

    List<Signal> findByInstanceIdAndSymbolAndDeletedFalseOrderByCreatedAtDesc(UUID instanceId, String symbol);

    @Query("SELECT s FROM Signal s WHERE s.status = 'PENDING' AND s.deleted = false ORDER BY s.createdAt ASC")
    List<Signal> findPendingSignals();
}
