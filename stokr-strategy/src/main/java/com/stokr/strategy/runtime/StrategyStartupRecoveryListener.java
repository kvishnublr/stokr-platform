package com.stokr.strategy.runtime;

import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyStartupRecoveryListener {

    private static final String RUNNING = "RUNNING";

    private final StrategyInstanceRepository instanceRepository;
    private final StrategyRuntimeCoordinator coordinator;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileStaleRuntimes() {
        for (var si : instanceRepository.findAllByRuntimeStateAndDeletedFalse(RUNNING)) {
            UUID id = si.getId();
            try {
                coordinator.recoverAfterCrash(id);
            } catch (Exception ex) {
                log.error("strategy.startup.recovery_failed instanceId={}", id, ex);
            }
        }
    }
}
