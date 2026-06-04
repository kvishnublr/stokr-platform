package com.stokr.strategy.operational;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StrategyRuntimeHealthService {

    private final StrategyRuntimeHealthRepository repository;
    private final StrategyExecutionModeService executionModeService;

    @Value("${stokr.strategy.session.zone:Asia/Kolkata}")
    private ZoneId zone;

    @Transactional
    public StrategyRuntimeHealth recordScanAttempt(String strategyKey, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setScansAttempted(row.getScansAttempted() + 1);
        row.setLastScanTime(at);
        row.setExecutionMode(executionModeService.modeFor(strategyKey).name());
        recomputeRejectionRate(row);
        row.setUpdatedAt(at);
        return repository.save(row);
    }

    @Transactional
    public void recordScanBlockedIntegrity(String strategyKey, String reason, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setScansBlockedIntegrity(row.getScansBlockedIntegrity() + 1);
        row.setLastRejectionReason(truncate(reason));
        row.setLastScanTime(at);
        recomputeRejectionRate(row);
        row.setUpdatedAt(at);
        repository.save(row);
    }

    @Transactional
    public void recordScanAllowed(String strategyKey, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setLastRejectionReason(null);
        row.setLastScanTime(at);
        row.setUpdatedAt(at);
        repository.save(row);
    }

    @Transactional
    public void recordScanBlockedFeed(String strategyKey, String reason, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setScansBlockedFeed(row.getScansBlockedFeed() + 1);
        row.setLastRejectionReason(truncate(reason));
        row.setLastScanTime(at);
        recomputeRejectionRate(row);
        row.setUpdatedAt(at);
        repository.save(row);
    }

    @Transactional
    public void recordSignalGenerated(String strategyKey, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setSignalsGenerated(row.getSignalsGenerated() + 1);
        row.setLastSignalTime(at);
        row.setUpdatedAt(at);
        repository.save(row);
    }

    @Transactional
    public void recordTradeOpened(String strategyKey, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setTradesOpened(row.getTradesOpened() + 1);
        row.setUpdatedAt(at);
        repository.save(row);
    }

    @Transactional
    public void recordTradeClosed(String strategyKey, long holdSeconds, Instant at) {
        StrategyRuntimeHealth row = getOrCreate(strategyKey, at);
        row.setTradesClosed(row.getTradesClosed() + 1);
        if (row.getAvgHoldSeconds() == null || row.getAvgHoldSeconds() == 0) {
            row.setAvgHoldSeconds(Math.max(0, holdSeconds));
        } else {
            long closed = row.getTradesClosed();
            long prevAvg = row.getAvgHoldSeconds();
            row.setAvgHoldSeconds(((prevAvg * (closed - 1)) + holdSeconds) / closed);
        }
        row.setUpdatedAt(at);
        repository.save(row);
    }

    public List<StrategyRuntimeHealth> healthForSession(LocalDate sessionDate) {
        return repository.findBySessionDateOrderByStrategyNameAsc(sessionDate);
    }

    public List<StrategyRuntimeHealth> healthForToday(Instant anchor) {
        return healthForSession(sessionDate(anchor));
    }

    private StrategyRuntimeHealth getOrCreate(String strategyKey, Instant at) {
        LocalDate session = sessionDate(at);
        return repository.findByStrategyNameAndSessionDate(strategyKey, session)
                .orElseGet(() -> {
                    StrategyRuntimeHealth created = new StrategyRuntimeHealth();
                    created.setStrategyName(strategyKey);
                    created.setSessionDate(session);
                    created.setExecutionMode(executionModeService.modeFor(strategyKey).name());
                    created.setCreatedAt(at);
                    created.setUpdatedAt(at);
                    return created;
                });
    }

    private void recomputeRejectionRate(StrategyRuntimeHealth row) {
        if (row.getScansAttempted() <= 0) {
            row.setRejectionRate(BigDecimal.ZERO);
            return;
        }
        long blocked = row.getScansBlockedIntegrity() + row.getScansBlockedFeed();
        row.setRejectionRate(BigDecimal.valueOf(blocked)
                .divide(BigDecimal.valueOf(row.getScansAttempted()), 4, RoundingMode.HALF_UP));
    }

    private LocalDate sessionDate(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 256 ? reason : reason.substring(0, 253) + "...";
    }
}
