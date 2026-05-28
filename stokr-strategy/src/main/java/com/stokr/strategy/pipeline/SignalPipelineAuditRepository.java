package com.stokr.strategy.pipeline;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SignalPipelineAuditRepository extends JpaRepository<SignalPipelineAudit, Long> {

    List<SignalPipelineAudit> findByCreatedAtAfterOrderByCreatedAtDesc(Instant since, Pageable pageable);

    List<SignalPipelineAudit> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID userId, Instant since, Pageable pageable);
}
