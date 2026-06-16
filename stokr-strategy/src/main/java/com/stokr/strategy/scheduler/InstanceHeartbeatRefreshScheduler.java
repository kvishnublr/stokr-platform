package com.stokr.strategy.scheduler;

import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Keeps RUNNING strategy instances alive by refreshing their heartbeat every 5 minutes.
 * Prevents StrategyStaleDetectionScheduler (10-min cutoff) from killing healthy instances
 * that have no external executor sending heartbeats.
 *
 * Uses a single bulk UPDATE instead of load-modify-saveAll: the entity round-trip lost
 * @Version races against concurrent instance writers (lifecycle service, pipeline) and
 * threw ObjectOptimisticLockingFailureException, which left heartbeats stale and caused
 * LIVE orders to be rejected with "No healthy LIVE strategy runtime".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceHeartbeatRefreshScheduler {

    private final StrategyInstanceRepository instanceRepository;

    @Scheduled(fixedDelayString = "${stokr.instance.heartbeat-refresh-ms:300000}")
    public void refreshHeartbeats() {
        try {
            int touched = instanceRepository.touchHeartbeatsForRunning(Instant.now());
            if (touched > 0) {
                log.debug("[HeartbeatRefresh] Touched {} RUNNING instances", touched);
            }
        } catch (Exception ex) {
            log.error("[HeartbeatRefresh] failed: {}", ex.getMessage(), ex);
        }
    }
}
