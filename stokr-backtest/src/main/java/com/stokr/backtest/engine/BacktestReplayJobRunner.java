package com.stokr.backtest.engine;

import com.stokr.backtest.service.BacktestJobExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BacktestReplayJobRunner {

    private final BacktestJobExecutionService backtestJobExecutionService;

    @Async
    public void run(UUID jobId) {
        backtestJobExecutionService.execute(jobId);
    }
}
