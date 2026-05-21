package com.stokr.strategy.scheduler;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import com.stokr.strategy.service.StrategyInstanceLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Automatically restarts enabled strategy instances that have fallen out of RUNNING state.
 * Runs every 30 seconds. Skips instances where start() fails (missing bindings, live gate blocked, etc.).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceAutoHealScheduler {

    private final StrategyInstanceRepository instanceRepository;
    private final StrategyInstanceLifecycleService lifecycleService;

    @Scheduled(fixedDelayString = "${stokr.instance.auto-heal-interval-ms:30000}")
    public void autoHealStoppedInstances() {
        List<StrategyInstance> candidates = instanceRepository.findAllEnabledNonRunning();
        if (candidates.isEmpty()) return;

        int healed = 0;
        for (StrategyInstance si : candidates) {
            try {
                lifecycleService.start(si.getUserId(), si.getId());
                healed++;
                log.info("[AutoHeal] Started instance {} ({}) for user {}",
                        si.getId(), si.getDefinition().getStrategyKey(), si.getUserId());
            } catch (Exception e) {
                log.debug("[AutoHeal] Skipped instance {} ({}): {}",
                        si.getId(), si.getDefinition().getStrategyKey(), e.getMessage());
            }
        }

        if (healed > 0) {
            log.info("[AutoHeal] Healed {}/{} instances", healed, candidates.size());
        }
    }
}
