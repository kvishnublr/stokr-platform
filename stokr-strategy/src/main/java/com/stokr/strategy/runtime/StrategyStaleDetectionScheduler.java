package com.stokr.strategy.runtime;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Marks orchestration ERROR when RUNNING instances miss heartbeats (external executor must call heartbeat).
 * Read for logging, then one atomic UPDATE — per-entity save() raced concurrent writers on @Version.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyStaleDetectionScheduler {

    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final StrategyInstanceRepository instanceRepository;

    @Scheduled(fixedDelayString = "${stokr.strategy.stale-check-ms:120000}")
    public void detectStale() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        List<StrategyInstance> stale = instanceRepository.findRunningWithStaleHeartbeat(cutoff);
        if (stale.isEmpty()) {
            return;
        }
        for (StrategyInstance si : stale) {
            log.warn("strategy.stale.stopped instanceId={} userId={} lastHeartbeat={}",
                    si.getId(), si.getUserId(), si.getHeartbeatAt());
        }
        List<UUID> ids = stale.stream().map(StrategyInstance::getId).toList();
        int stopped = instanceRepository.markStaleStopped(ids, Instant.now());
        log.warn("strategy.stale.batch_stopped candidates={} stopped={}", ids.size(), stopped);
    }
}
