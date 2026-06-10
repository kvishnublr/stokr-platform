package com.stokr.common.health;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * DTO representing health status of a microservice.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceHealth {
    private String serviceName;
    private String status; // "UP", "DEGRADED", "DOWN"
    private Integer instances; // Number of instances running
    private Long responseTimeMs; // Average response time
    private Instant lastHealthCheck;
    private String details; // Additional details if degraded/down
    private Integer activeConnections; // For WebSocket service
    private Integer processingQueueDepth; // For Execution/Strategy services
}
