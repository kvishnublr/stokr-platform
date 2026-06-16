package com.stokr.admin.repository;

import com.stokr.admin.domain.MarketBackfillJobSymbol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketBackfillJobSymbolRepository extends JpaRepository<MarketBackfillJobSymbol, UUID> {
    List<MarketBackfillJobSymbol> findByJobIdAndDeletedFalseOrderByUpdatedAtDesc(UUID jobId);
    Optional<MarketBackfillJobSymbol> findByJobIdAndSymbolAndDeletedFalse(UUID jobId, String symbol);
}
