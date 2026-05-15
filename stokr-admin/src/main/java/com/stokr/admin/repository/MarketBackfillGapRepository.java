package com.stokr.admin.repository;

import com.stokr.admin.domain.MarketBackfillGap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketBackfillGapRepository extends JpaRepository<MarketBackfillGap, UUID> {
    List<MarketBackfillGap> findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(UUID jobId);
    long countByJobIdAndDeletedFalse(UUID jobId);
}
