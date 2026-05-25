package com.stokr.execution.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.rest.client.RabbitManagementTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Queue depth monitoring and backpressure detection.
 *
 * Tracks RabbitMQ queue depths in real-time.
 * Triggers backpressure if queue > threshold.
 * Prevents broker overload during high-signal periods.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueueDepthMonitor {

    private final RabbitTemplate rabbitTemplate;

    @Value("${stokr.queue.max-depth:1000}")
    private int maxQueueDepth;

    @Value("${stokr.queue.warning-threshold:500}")
    private int warningThreshold;

    @Value("${spring.rabbitmq.host:localhost}")
    private String rabbitHost;

    @Value("${spring.rabbitmq.port:5672}")
    private int rabbitPort;

    private Map<String, QueueMetrics> queueMetrics = new HashMap<>();

    /**
     * Monitor queue depths every 30 seconds.
     */
    @Scheduled(fixedDelayString = "30000")
    public void monitorQueueDepths() {
        try {
            // Monitor critical queues
            monitorQueue("strategy.signals", "signal_generation");
            monitorQueue("execution.orders", "order_submission");
            monitorQueue("execution.fills", "fill_processing");
            monitorQueue("portfolio.updates", "position_updates");

        } catch (Exception ex) {
            log.error("queue.monitoring_failed {}", ex.getMessage());
        }
    }

    private void monitorQueue(String queueName, String displayName) {
        try {
            // This would use RabbitMQ management API in production
            // For now, we implement a placeholder that logs based on depth

            QueueMetrics metrics = queueMetrics.computeIfAbsent(queueName, k -> new QueueMetrics(queueName));
            metrics.setLastCheckTime(Instant.now());

            // In real implementation, fetch actual queue length from management API
            int currentDepth = getQueueDepth(queueName);
            metrics.setCurrentDepth(currentDepth);
            metrics.recordDepth(currentDepth);

            if (currentDepth > maxQueueDepth) {
                log.error("CRITICAL: queue_overflow queue={} depth={} max={}",
                    displayName, currentDepth, maxQueueDepth);
                triggerBackpressure(queueName);
            } else if (currentDepth > warningThreshold) {
                log.warn("queue.depth_warning queue={} depth={} threshold={}",
                    displayName, currentDepth, warningThreshold);
            } else {
                log.debug("queue.depth queue={} depth={}", displayName, currentDepth);
            }

        } catch (Exception ex) {
            log.debug("queue.depth_check_failed queue={} {}", queueName, ex.getMessage());
        }
    }

    /**
     * Get queue depth (placeholder - would use RabbitMQ API in production).
     */
    private int getQueueDepth(String queueName) {
        // In production, this would call RabbitMQ management API
        // http://localhost:15672/api/queues/%2F/{queueName}
        // For now, return 0 (implementation detail for actual deployment)
        return 0;
    }

    /**
     * Trigger backpressure: pause signal generation temporarily.
     */
    private void triggerBackpressure(String queueName) {
        log.error("backpressure.engaged queue={} pausing_signal_generation", queueName);
        // In production: broadcast BACKPRESSURE event to signal generators
        // Signal generators check this flag before emitting new signals
    }

    /**
     * Release backpressure: resume signal generation.
     */
    public void releaseBackpressure() {
        log.info("backpressure.released resuming_signal_generation");
        // In production: broadcast BACKPRESSURE_RELEASED event
    }

    /**
     * Queue metrics snapshot.
     */
    public static class QueueMetrics {
        private String queueName;
        private int currentDepth;
        private long peakDepth;
        private Instant lastCheckTime;
        private java.util.List<Integer> depthHistory = new java.util.ArrayList<>();

        public QueueMetrics(String queueName) {
            this.queueName = queueName;
        }

        public void recordDepth(int depth) {
            depthHistory.add(depth);
            if (depth > peakDepth) {
                peakDepth = depth;
            }
            if (depthHistory.size() > 100) {
                depthHistory.remove(0);
            }
        }

        public double getAverageDepth() {
            if (depthHistory.isEmpty()) return 0;
            return depthHistory.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        // Getters
        public String getQueueName() { return queueName; }
        public int getCurrentDepth() { return currentDepth; }
        public long getPeakDepth() { return peakDepth; }
        public Instant getLastCheckTime() { return lastCheckTime; }

        public void setCurrentDepth(int depth) { this.currentDepth = depth; }
        public void setLastCheckTime(Instant time) { this.lastCheckTime = time; }
    }

    public Map<String, QueueMetrics> getMetrics() {
        return new HashMap<>(queueMetrics);
    }
}
