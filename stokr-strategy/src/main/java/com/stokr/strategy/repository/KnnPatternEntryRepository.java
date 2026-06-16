package com.stokr.strategy.repository;

import com.stokr.strategy.domain.KnnPatternEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KnnPatternEntryRepository extends JpaRepository<KnnPatternEntry, UUID> {
    List<KnnPatternEntry> findByStrategyKeyOrderByTradeTimeAsc(String strategyKey);
    long countByStrategyKey(String strategyKey);
}
