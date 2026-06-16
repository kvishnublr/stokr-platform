package com.stokr.execution.orphan.scheduler;

import com.stokr.execution.orphan.domain.OrphanReviewTask;
import com.stokr.execution.orphan.repository.OrphanReviewTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stokr.orphan.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class StaleReviewTaskCleanupScheduler {

    private final OrphanReviewTaskRepository reviewTaskRepository;

    @Scheduled(cron = "${stokr.orphan.cleanup.cron:0 0 1 * * *}")
    public void expireStaleReviewTasks() {
        log.info("stale_review_cleanup.started");

        try {
            Instant threshold = Instant.now();
            List<OrphanReviewTask> staleTasks = reviewTaskRepository.findStaleTasks(threshold);

            int expiredCount = 0;
            for (OrphanReviewTask task : staleTasks) {
                task.setStatus("EXPIRED");
                reviewTaskRepository.save(task);
                expiredCount++;
            }

            log.info("stale_review_cleanup.completed expired_count={}", expiredCount);
        } catch (Exception e) {
            log.error("stale_review_cleanup.failed", e);
        }
    }
}
