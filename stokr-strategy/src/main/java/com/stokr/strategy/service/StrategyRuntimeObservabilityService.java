package com.stokr.strategy.service;

import com.stokr.oms.domain.PortfolioPosition;
import com.stokr.oms.repository.PortfolioPositionRepository;
import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.domain.StrategySignalEntity;
import com.stokr.strategy.dto.StrategyRuntimeMetricsDto;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.repository.StrategySignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StrategyRuntimeObservabilityService {

    private final StrategyInstanceRepository instanceRepository;
    private final StrategySignalRepository signalRepository;
    private final PortfolioPositionRepository portfolioPositionRepository;

    public List<StrategyRuntimeMetricsDto> metricsForUser(UUID userId) {
        List<StrategyInstance> instances = instanceRepository.findAllForUserWithDefinition(userId);
        List<StrategyRuntimeMetricsDto> out = new ArrayList<>();
        for (StrategyInstance si : instances) {
            long sigCount = signalRepository.countByInstanceId(si.getId());
            Instant lastSig = signalRepository.findFirstByInstance_IdAndDeletedFalseOrderByCreatedAtDesc(si.getId())
                    .map(StrategySignalEntity::getCreatedAt)
                    .orElse(null);
            BigDecimal uPnL = portfolioPositionRepository.findByUserIdAndSymbolAndDeletedFalse(userId, si.getSymbol())
                    .map(PortfolioPosition::getUnrealizedPnl)
                    .orElse(null);
            Instant started = si.getStartedAt();
            Long uptime = null;
            if (started != null && "RUNNING".equalsIgnoreCase(si.getRuntimeState())) {
                uptime = Duration.between(started, Instant.now()).getSeconds();
            }
            String health = computeHealth(si.getRuntimeState(), lastSig);
            out.add(new StrategyRuntimeMetricsDto(
                    si.getId(),
                    si.getDefinition().getId(),
                    si.getDefinition().getStrategyKey(),
                    si.getSymbol(),
                    si.getExecutionMode(),
                    si.getRuntimeState(),
                    started,
                    si.getStoppedAt(),
                    uptime,
                    sigCount,
                    lastSig,
                    uPnL,
                    health
            ));
        }
        return out;
    }

    private static String computeHealth(String runtimeState, Instant lastSignalAt) {
        if (!"RUNNING".equalsIgnoreCase(runtimeState != null ? runtimeState : "")) {
            return "IDLE";
        }
        if (lastSignalAt == null) {
            return "WARN";
        }
        if (Duration.between(lastSignalAt, Instant.now()).toHours() > 48) {
            return "STALE";
        }
        return "OK";
    }
}
