package com.stokr.chartink;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChartinkPositionRepository extends JpaRepository<ChartinkPosition, Long> {

    List<ChartinkPosition> findByStatusOrderByCreatedAtDesc(String status);

    Optional<ChartinkPosition> findBySymbolAndStatus(String symbol, String status);

    boolean existsBySymbolAndStatus(String symbol, String status);

    long countByStatus(String status);
}
