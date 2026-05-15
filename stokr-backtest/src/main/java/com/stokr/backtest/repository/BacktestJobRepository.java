package com.stokr.backtest.repository;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BacktestJobRepository extends JpaRepository<BacktestJob, UUID> {

    Optional<BacktestJob> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);

    List<BacktestJob> findAllByStatusAndDeletedFalse(BacktestJobStatus status);

    long countByDeletedFalse();

    long countByStatusAndDeletedFalse(BacktestJobStatus status);

    List<BacktestJob> findTop15ByDeletedFalseOrderByUpdatedAtDesc();

    Optional<BacktestJob> findFirstByUserIdAndDeletedFalseOrderByUpdatedAtDesc(UUID userId);

    List<BacktestJob> findTop12ByStatusInAndDeletedFalseOrderByUpdatedAtDesc(Collection<BacktestJobStatus> statuses);
}
