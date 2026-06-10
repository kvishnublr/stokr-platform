package com.stokr.common.health;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;

/**
 * Comprehensive health response for admin dashboard.
 * Shows status of all services, queues, and infrastructure.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthResponse {
    private String overallStatus; // "UP", "DEGRADED", "DOWN"
    private Instant timestamp;

    // Service Health
    private List<ServiceHealth> services;

    // Queue Health
    private List<QueueHealth> queues;

    // Infrastructure
    private InfrastructureHealth infrastructure;

    // Alerts
    private List<Alert> alerts;

    // Metadata
    private String systemVersion;
    private Long uptime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InfrastructureHealth {
        private String databaseStatus; // "UP", "DOWN", "SLOW"
        private Long databaseResponseTimeMs;

        private String rabbitmqStatus; // "UP", "DOWN", "SLOW"
        private Long rabbitmqResponseTimeMs;

        private String cacheStatus; // "UP", "DOWN" (Redis if used)
        private Long cacheResponseTimeMs;

        private Double cpuUsage; // Percentage
        private Double memoryUsage; // Percentage
        private Double diskUsage; // Percentage
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Alert {
        private String severity; // "INFO", "WARNING", "CRITICAL"
        private String message;
        private String affectedComponent; // Service or queue name
        private Instant detectedAt;
    }
}
