package com.stokr.strategy.runtime;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Marks orchestration ERROR when RUNNING instances miss heartbeats (external executor must call heartbeat).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyStaleDetectionScheduler {

    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final StrategyInstanceRepository instanceRepository;

    @Scheduled(fixedDelayString = "${stokr.strategy.stale-check-ms:120000}")
    @Transactional
    public void detectStale() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        List<StrategyInstance> stale = instanceRepository.findRunningWithStaleHeartbeat(cutoff);
        for (StrategyInstance si : stale) {
            si.setOrchestrationState("ERROR");
            si.setRuntimeState("STOPPED");
            instanceRepository.save(si);
            log.warn("strategy.stale.stopped instanceId={} userId={} lastHeartbeat={}",
                    si.getId(), si.getUserId(), si.getHeartbeatAt());
        }
    }
}
