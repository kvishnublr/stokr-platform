package com.stokr.marketdata.tick;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TickAnomalyRepository extends JpaRepository<TickAnomaly, Long> {

    List<TickAnomaly> findBySymbolAndDetectedTsBetweenOrderByDetectedTsDesc(
        String symbol, LocalDateTime from, LocalDateTime to);

    List<TickAnomaly> findByResolvedAndDetectedTsAfter(boolean resolved, LocalDateTime after);

    long countBySymbolAndAnomalyTypeAndDetectedTsBetween(
        String symbol, String anomalyType, LocalDateTime from, LocalDateTime to);
}
