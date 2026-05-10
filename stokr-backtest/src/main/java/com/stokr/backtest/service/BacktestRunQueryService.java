package com.stokr.backtest.service;

import com.stokr.backtest.domain.BacktestMetrics;
import com.stokr.backtest.domain.BacktestRun;
import com.stokr.backtest.repository.BacktestMetricsRepository;
import com.stokr.backtest.repository.BacktestRunRepository;
import com.stokr.backtest.web.dto.BacktestRunSummaryDto;
import com.stokr.common.exception.ForbiddenException;
import com.stokr.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BacktestRunQueryService {

    private final BacktestRunRepository runRepository;
    private final BacktestMetricsRepository metricsRepository;
    private final BacktestResultService backtestResultService;

    @Transactional(readOnly = true)
    public Page<BacktestRunSummaryDto> pageForUser(UUID userId, Pageable pageable) {
        return runRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public BacktestReplayOutcome detailForUser(UUID runId, UUID userId) {
        BacktestRun run = runRepository.findById(runId).orElseThrow(() -> new NotFoundException("Run not found"));
        if (run.getUserId() == null || !run.getUserId().equals(userId)) {
            throw new ForbiddenException("Not your backtest run");
        }
        return backtestResultService.loadMaterializedOutcome(run);
    }

    private BacktestRunSummaryDto toSummary(BacktestRun r) {
        String hashPreview = metricsRepository.findByRun_IdAndDeletedFalse(r.getId())
                .map(BacktestMetrics::getReplayHash)
                .filter(h -> h != null && !h.isBlank())
                .map(h -> h.length() > 16 ? h.substring(0, 16) + "…" : h)
                .orElse(null);
        return new BacktestRunSummaryDto(
                r.getId(),
                r.getStrategyKey(),
                r.getSymbol(),
                r.getStatus(),
                r.getSeed(),
                r.getTimeframe(),
                r.getRangeStart(),
                r.getRangeEnd(),
                r.getCreatedAt(),
                hashPreview
        );
    }
}
