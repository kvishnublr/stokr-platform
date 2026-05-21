package com.stokr.strategy.scheduler;

import com.stokr.strategy.domain.StrategyInstance;
import com.stokr.strategy.repository.StrategyInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Keeps RUNNING strategy instances alive by refreshing their heartbeat every 5 minutes.
 * Prevents StrategyStaleDetectionScheduler (10-min cutoff) from killing healthy instances
 * that have no external executor sending heartbeats.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceHeartbeatRefreshScheduler {

    private final StrategyInstanceRepository instanceRepository;

    @Scheduled(fixedDelayString = "${stokr.instance.heartbeat-refresh-ms:300000}")
    @Transactional
    public void refreshHeartbeats() {
        List<StrategyInstance> running = instanceRepository.findAllByRuntimeStateAndDeletedFalse("RUNNING");
        if (running.isEmpty()) return;

        Instant now = Instant.now();
        for (StrategyInstance si : running) {
            si.setHeartbeatAt(now);
        }
        instanceRepository.saveAll(running);
        log.debug("[HeartbeatRefresh] Touched {} RUNNING instances", running.size());
    }
}
