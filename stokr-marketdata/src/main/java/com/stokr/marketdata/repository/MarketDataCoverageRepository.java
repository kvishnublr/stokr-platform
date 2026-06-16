package com.stokr.marketdata.repository;

import com.stokr.marketdata.domain.MarketDataCoverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketDataCoverageRepository extends JpaRepository<MarketDataCoverage, UUID> {
    Optional<MarketDataCoverage> findBySymbolAndTimeframeAndDeletedFalse(String symbol, String timeframe);

    List<MarketDataCoverage> findTop200ByDeletedFalseOrderByUpdatedAtDesc();
}

