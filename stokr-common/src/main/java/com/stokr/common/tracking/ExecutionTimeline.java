package com.stokr.common.tracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;

/**
 * Represents the complete timeline of a signal from generation to position creation.
 * Used for debugging and performance analysis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionTimeline {
    private String signalId;
    private String orderId;
    private String symbol;
    private String side;
    private Integer quantity;

    // Timeline events
    private List<TimelineEvent> events;

    // Summary
    private Long totalDurationMs;
    private Boolean successful;
    private String failureReason;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimelineEvent {
        private Integer sequence; // Order in timeline
        private Instant timestamp;
        private String service; // "strategy-service", "execution-service", "broker", etc.
        private String event; // "SIGNAL_GENERATED", "SIGNAL_QUEUED", "RISK_CHECK_STARTED", etc.
        private String status; // "SUCCESS", "PENDING", "FAILED"
        private Long durationMsFromPrevious; // Time since last event
        private String details; // Additional details
        private String errorMessage; // If failed
    }
}
