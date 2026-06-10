package com.stokr.common.messaging.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Audit event published by any service for logging and compliance.
 * Consumed by Audit Logger to persist to database.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    // Audit identification
    private String auditId;
    private Instant timestamp;

    // Action details
    private String action; // "SIGNAL_GENERATED", "ORDER_PLACED", "ORDER_FILLED", "POSITION_CLOSED", etc.
    private String actor; // "strategy-service", "execution-service", "user-terminal", "system"
    private String actorInstance; // Which instance/pod executed this

    // Entity references
    private String entityType; // "SIGNAL", "ORDER", "POSITION", "RISK_DECISION", "USER"
    private String entityId; // Signal ID, Order ID, Position ID, etc.

    // Details
    private Map<String, Object> details; // JSON details of what happened

    // User context
    private String userId;
    private String traderId;

    // Trading context
    private String symbol;
    private String strategyName;

    // Tracing
    private String correlationId;
    private String parentAuditId; // If this audit is related to another audit

    // Compliance
    private String changeType; // "CREATE", "UPDATE", "DELETE", "EXECUTE"
    private String previousState; // JSON of state before change
    private String newState; // JSON of state after change

    // Severity
    private String severity; // "INFO", "WARNING", "ERROR", "CRITICAL"
}
