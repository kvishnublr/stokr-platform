package com.stokr.bootstrap.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SignalPipelineStartupRecovery {

    private final OrphanedSignalRedispatchService orphanedSignalRedispatchService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanSignalsAfterStartup() {
        try {
            var result = orphanedSignalRedispatchService.redispatchSessionOrphans(java.time.Instant.now());
            log.info("signal_pipeline.startup_orphan_recovery redispatched={} candidates={}",
                    result.get("redispatched"), result.get("candidates"));
        } catch (Exception ex) {
            log.warn("signal_pipeline.startup_orphan_recovery_failed {}", ex.toString());
        }
    }
}
