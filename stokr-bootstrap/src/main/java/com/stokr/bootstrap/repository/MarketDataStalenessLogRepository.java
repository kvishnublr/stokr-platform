package com.stokr.bootstrap.repository;

import com.stokr.bootstrap.domain.entity.MarketDataStalenessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MarketDataStalenessLogRepository extends JpaRepository<MarketDataStalenessLog, UUID> {

    List<MarketDataStalenessLog> findBySymbolOrderByCheckTimeDesc(String symbol);

    List<MarketDataStalenessLog> findByIsStaleTrue();

    List<MarketDataStalenessLog> findByCheckTimeAfter(LocalDateTime time);
}
