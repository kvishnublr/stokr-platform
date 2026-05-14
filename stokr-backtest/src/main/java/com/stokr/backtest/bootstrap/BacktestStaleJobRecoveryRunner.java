package com.stokr.backtest.bootstrap;

import com.stokr.backtest.domain.BacktestJob;
import com.stokr.backtest.domain.BacktestJobStatus;
import com.stokr.backtest.repository.BacktestJobRepository;
import com.stokr.backtest.service.BacktestJobStatusWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Marks async jobs that were left {@link BacktestJobStatus#RUNNING} across JVM restarts as failed so operators can resume runs explicitly.
 */
@Component
@Order(50)
@RequiredArgsConstructor
@Slf4j
public class BacktestStaleJobRecoveryRunner implements ApplicationRunner {

    private final BacktestJobRepository backtestJobRepository;
    private final BacktestJobStatusWriter backtestJobStatusWriter;

    @Override
    public void run(ApplicationArguments args) {
        List<BacktestJob> running = backtestJobRepository.findAllByStatusAndDeletedFalse(BacktestJobStatus.RUNNING);
        if (running.isEmpty()) {
            return;
        }
        log.warn("backtest.jobs.recover_stale count={}", running.size());
        for (BacktestJob j : running) {
            backtestJobStatusWriter.markInterruptedByRestart(j.getId());
        }
    }
}
