package com.stokr.backtest.repository;

import com.stokr.backtest.domain.BacktestRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, UUID> {

    Page<BacktestRun> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
