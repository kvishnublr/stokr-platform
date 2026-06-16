package com.stokr.strategy.runtime;

import com.stokr.strategy.dto.StrategyRestoreBundle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Facade for orchestration hooks (recovery, scheduling). Workers call through here for consistent lifecycle semantics.
 */
@Component
@RequiredArgsConstructor
public class StrategyRuntimeCoordinator {

    private final RuntimeRecoveryService runtimeRecoveryService;
    private final RuntimeScheduler runtimeScheduler;

    public Optional<StrategyRestoreBundle> recoverAfterCrash(UUID instanceId) {
        return runtimeScheduler.withInstanceLock(instanceId, () -> runtimeRecoveryService.recoverInstance(instanceId));
    }
}
