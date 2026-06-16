package com.stokr.backtest.repository;

import com.stokr.backtest.domain.BacktestResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BacktestResultRepository extends JpaRepository<BacktestResult, UUID> {

    Optional<BacktestResult> findByRun_IdAndDeletedFalse(UUID runId);
}
