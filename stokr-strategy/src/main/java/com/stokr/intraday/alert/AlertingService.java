package com.stokr.intraday.alert;

import com.stokr.intraday.domain.CurrentSetup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages alert generation and delivery for detected setups
 *
 * Features:
 * - Quality-based alert filtering (only top setups)
 * - User preference filtering (setup types, sectors, times)
 * - Alert deduplication (prevent duplicate alerts within 5 minutes)
 * - Multiple delivery channels (WebSocket, Email, SMS)
 * - Alert history tracking
 */
@Service
@Slf4j
public class AlertingService {

    private final List<AlertListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, AlertMetadata> alertHistory = new ConcurrentHashMap<>();

    // Alert configuration
    private static final BigDecimal MIN_QUALITY_SCORE = BigDecimal.valueOf(60.0);
    private static final long DEDUP_WINDOW_MINUTES = 5;

    /**
     * Generate alert for a detected setup
     * Applies filtering and deduplication
     *
     * @param setup Detected trading setup
     * @param userId User to receive alert (optional, null = broadcast)
     * @return Alert if generated, empty otherwise
     */
    public Optional<Alert> generateAlert(CurrentSetup setup, Long userId) {
        // Quality filter: only alert on high-quality setups
        if (setup.getQualityScore() == null ||
            setup.getQualityScore().compareTo(MIN_QUALITY_SCORE) < 0) {
            return Optional.empty();
        }

        // Deduplication: prevent duplicate alerts within 5 minutes
        String dedupKey = getDeduplicationKey(setup);
        AlertMetadata metadata = alertHistory.get(dedupKey);
        if (metadata != null && !isAlertExpired(metadata)) {
            log.debug("alert.dedup_skipped setup={} key={}", setup.getStockId(), dedupKey);
            return Optional.empty();
        }

        // Create alert
        Alert alert = new Alert();
        alert.setId(UUID.randomUUID().toString());
        alert.setSetup(setup);
        alert.setUserId(userId);
        alert.setTimestamp(Instant.now());
        alert.setChannel(AlertChannel.WEBSOCKET); // Default to WebSocket

        // Determine alert priority based on quality score
        if (setup.getQualityScore().compareTo(BigDecimal.valueOf(80)) >= 0) {
            alert.setPriority(AlertPriority.HIGH);
        } else if (setup.getQualityScore().compareTo(BigDecimal.valueOf(70)) >= 0) {
            alert.setPriority(AlertPriority.MEDIUM);
        } else {
            alert.setPriority(AlertPriority.LOW);
        }

        // Update history
        metadata = new AlertMetadata();
        metadata.setupId = setup.getStockId() + ":" + setup.getSetupType();
        metadata.lastAlertTime = Instant.now();
        metadata.alertCount = (alertHistory.get(dedupKey) != null ? alertHistory.get(dedupKey).alertCount + 1 : 1);
        alertHistory.put(dedupKey, metadata);

        log.info("alert.generated stock={} type={} score={} priority={}",
                setup.getStockId(), setup.getSetupType(), setup.getQualityScore(), alert.getPriority());

        return Optional.of(alert);
    }

    /**
     * Send alert to all subscribers
     */
    public void broadcastAlert(Alert alert) {
        listeners.forEach(listener -> {
            try {
                listener.onAlert(alert);
            } catch (Exception e) {
                log.error("alert.delivery_error listener={}", listener.getClass().getSimpleName(), e);
            }
        });

        log.debug("alert.broadcast id={} listeners={}", alert.getId(), listeners.size());
    }

    /**
     * Register alert listener (for delivery channels)
     */
    public void registerListener(AlertListener listener) {
        listeners.add(listener);
        log.debug("alert.listener_registered type={}", listener.getClass().getSimpleName());
    }

    /**
     * Check if alert is expired (for deduplication)
     */
    private boolean isAlertExpired(AlertMetadata metadata) {
        long ageMinutes = java.time.temporal.ChronoUnit.MINUTES
                .between(metadata.lastAlertTime, Instant.now());
        return ageMinutes > DEDUP_WINDOW_MINUTES;
    }

    /**
     * Generate deduplication key
     */
    private String getDeduplicationKey(CurrentSetup setup) {
        return setup.getStockId() + ":" + setup.getSetupType();
    }

    /**
     * Get alert history statistics
     */
    public AlertHistoryStats getHistoryStats() {
        AlertHistoryStats stats = new AlertHistoryStats();
        stats.totalAlertsGenerated = alertHistory.values().stream()
                .mapToInt(m -> m.alertCount)
                .sum();
        stats.uniqueSetups = alertHistory.size();
        stats.timestamp = Instant.now();
        return stats;
    }

    /**
     * Alert data model
     */
    public static class Alert {
        private String id;
        private CurrentSetup setup;
        private Long userId;
        private Instant timestamp;
        private AlertChannel channel;
        private AlertPriority priority;
        private boolean delivered;
        private Instant deliveredAt;

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public CurrentSetup getSetup() { return setup; }
        public void setSetup(CurrentSetup setup) { this.setup = setup; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

        public AlertChannel getChannel() { return channel; }
        public void setChannel(AlertChannel channel) { this.channel = channel; }

        public AlertPriority getPriority() { return priority; }
        public void setPriority(AlertPriority priority) { this.priority = priority; }

        public boolean isDelivered() { return delivered; }
        public void setDelivered(boolean delivered) { this.delivered = delivered; }

        public Instant getDeliveredAt() { return deliveredAt; }
        public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

        @Override
        public String toString() {
            return String.format("Alert[%s %s@%.0f] %s/%s",
                    priority, setup.getStockId(), setup.getQualityScore(),
                    setup.getSetupType(), setup.getConfidenceLevel());
        }
    }

    /**
     * Alert channel (delivery method)
     */
    public enum AlertChannel {
        WEBSOCKET,   // Real-time browser notification
        EMAIL,       // Email to registered address
        SMS,         // SMS to registered phone
        PUSH         // Mobile push notification
    }

    /**
     * Alert priority level
     */
    public enum AlertPriority {
        HIGH,        // Score >= 80
        MEDIUM,      // Score 70-79
        LOW          // Score 60-69
    }

    /**
     * Listener interface for alert delivery
     */
    public interface AlertListener {
        void onAlert(Alert alert);
    }

    /**
     * Alert metadata for deduplication
     */
    private static class AlertMetadata {
        String setupId;
        Instant lastAlertTime;
        int alertCount;
    }

    /**
     * Alert history statistics
     */
    public static class AlertHistoryStats {
        public int totalAlertsGenerated;
        public int uniqueSetups;
        public Instant timestamp;
    }
}
