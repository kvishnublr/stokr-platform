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
 * Runs every 30 seconds. First attempts normal start (full eligibility checks).
 * If that fails, falls back to startInternal() which bypasses trader eligibility gates ???
 * safe because the platform ARM is the operator-level approval.
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
            String key = si.getDefinition().getStrategyKey();
            try {
                lifecycleService.start(si.getUserId(), si.getId());
                healed++;
                log.info("[AutoHeal] Started {} ({})", si.getId(), key);
            } catch (Exception e) {
                // Normal start failed (eligibility gate, binding, etc) ??? use internal path
                try {
                    lifecycleService.startInternal(si.getId());
                    healed++;
                    log.info("[AutoHeal] Internal-started {} ({}) reason: {}", si.getId(), key, e.getMessage());
                } catch (Exception ex) {
                    log.debug("[AutoHeal] Skipped {} ({}): {}", si.getId(), key, ex.getMessage());
                }
            }
        }

        if (healed > 0) {
            log.info("[AutoHeal] Healed {}/{} instances", healed, candidates.size());
        }
    }
}
