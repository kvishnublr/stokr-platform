package com.stokr.admin.repository;

import com.stokr.admin.domain.MarketBackfillFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketBackfillFailureRepository extends JpaRepository<MarketBackfillFailure, UUID> {
    List<MarketBackfillFailure> findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(UUID jobId);
    Optional<MarketBackfillFailure> findByJobIdAndSymbolAndFailureCodeAndDeletedFalse(UUID jobId, String symbol, String failureCode);
    long countByJobIdAndDeletedFalse(UUID jobId);
}
