package com.stokr.common.health;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * DTO representing health status of a RabbitMQ queue.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueHealth {
    private String queueName;
    private String status; // "HEALTHY", "WARNING", "CRITICAL"
    private Long pendingMessages;
    private Integer consumerCount;
    private Double processingRatePerSec; // Messages processed per second
    private Long avgMessageAgeMs; // Average age of messages in queue
    private Long estimatedClearTimeSeconds; // How long until queue is empty
    private Long maxQueueLength; // Max capacity
    private Boolean isBackingUp; // If pending messages > warning threshold
    private Long deadLetterCount; // Messages in DLQ
    private Instant lastUpdate;
    private String alertMessage; // Alert if backing up
}
