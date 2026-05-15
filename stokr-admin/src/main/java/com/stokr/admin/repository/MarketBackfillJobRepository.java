package com.stokr.admin.repository;

import com.stokr.admin.domain.MarketBackfillJob;
import com.stokr.admin.domain.MarketBackfillJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketBackfillJobRepository extends JpaRepository<MarketBackfillJob, UUID> {
    Optional<MarketBackfillJob> findByIdAndDeletedFalse(UUID id);
    List<MarketBackfillJob> findTop50ByDeletedFalseOrderByUpdatedAtDesc();
    long countByStatusAndDeletedFalse(MarketBackfillJobStatus status);
}
