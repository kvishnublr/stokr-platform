package com.stokr.marketdata.repository;

import com.stokr.marketdata.domain.MarketdataTick;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MarketdataTickRepository extends JpaRepository<MarketdataTick, UUID> {

    List<MarketdataTick> findBySymbolAndTickTimeBetweenOrderByTickTimeAsc(String symbol, Instant start, Instant end);
}
