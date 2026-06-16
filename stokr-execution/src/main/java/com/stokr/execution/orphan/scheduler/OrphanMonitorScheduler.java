package com.stokr.execution.orphan.scheduler;

import com.stokr.execution.orphan.domain.DetectedOrphanPosition;
import com.stokr.execution.orphan.domain.OrphanClassification;
import com.stokr.execution.orphan.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stokr.orphan.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class OrphanMonitorScheduler {

    private final OrphanPositionDetectionService detectionService;
    private final OrphanClassificationService classificationService;
    private final OperatorReviewWorkflowService reviewWorkflowService;

    @Scheduled(cron = "${stokr.orphan.monitor.cron:0 * * * * *}")
    public void scanAndClassifyOrphans() {
        log.info("orphan_monitor.started");
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Detect orphans
            List<DetectedOrphanPosition> detectedOrphans = detectionService.scanAllOrphans();
            log.info("orphan_monitor.detection.completed count={}", detectedOrphans.size());

            // Step 2: Classify each orphan
            int classifiedCount = 0;
            int doNotTouchCount = 0;
            Map<String, Integer> classificationCounts = new HashMap<>();

            for (DetectedOrphanPosition orphan : detectedOrphans) {
                try {
                    OrphanClassification classification = classificationService.classify(orphan);
                    classifiedCount++;

                    // Track classification types
                    String type = classification.getClassificationType();
                    classificationCounts.merge(type, 1, Integer::sum);

                    // Step 3: Create review tasks if needed
                    if ("DO_NOT_TOUCH".equals(classification.getStatus())) {
                        doNotTouchCount++;
                        reviewWorkflowService.createReviewTask(classification);
                        log.warn("orphan.do_not_touch.detected orphan_id={} symbol={}", orphan.getId(), orphan.getSymbol());
                    } else if (classification.getEvidenceScore() >= 85) {
                        reviewWorkflowService.createReviewTask(classification);
                    }
                } catch (Exception e) {
                    log.error("orphan.classification.failed orphan_id={}", orphan.getId(), e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("orphan_monitor.completed duration={}ms total_detected={} classified={} do_not_touch={} breakdown={}",
                    duration, detectedOrphans.size(), classifiedCount, doNotTouchCount, classificationCounts);

        } catch (Exception e) {
            log.error("orphan_monitor.failed", e);
        }
    }
}
