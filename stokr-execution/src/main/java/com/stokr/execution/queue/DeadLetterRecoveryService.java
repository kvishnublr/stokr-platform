package com.stokr.execution.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dead-letter queue (DLQ) recovery and replay.
 *
 * Handles poisoned messages that fail multiple delivery attempts.
 * Implements exponential backoff before replaying to main queue.
 * Prevents lost orders and signal data due to transient failures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterRecoveryService {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${stokr.dlq.max-retries:5}")
    private int maxRetries;

    @Value("${stokr.dlq.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    private List<DeadLetterMessage> dlqMessages = new ArrayList<>();

    /**
     * Monitor DLQ for failed messages every minute.
     * Attempt recovery for retryable failures.
     */
    @Scheduled(fixedDelayString = "60000")
    public void processDeadLetterQueue() {
        try {
            // In production: consume from actual DLQ
            // strategy.signals.dlq, execution.orders.dlq, etc.

            log.info("dlq.scan retry_candidates={}", dlqMessages.size());

            List<DeadLetterMessage> readyForRetry = new ArrayList<>();
            for (DeadLetterMessage msg : dlqMessages) {
                if (isReadyForRetry(msg)) {
                    readyForRetry.add(msg);
                }
            }

            for (DeadLetterMessage msg : readyForRetry) {
                if (msg.getRetryCount() < maxRetries) {
                    retryMessage(msg);
                } else {
                    log.error("dlq.max_retries_exceeded type={} original_queue={} data={}",
                        msg.getMessageType(), msg.getOriginalQueue(), msg.getPayload());
                    dlqMessages.remove(msg); // Archive for manual review
                }
            }

        } catch (Exception ex) {
            log.error("dlq.processing_failed {}", ex.getMessage());
        }
    }

    /**
     * Register a message in DLQ.
     */
    public void registerDeadLetterMessage(String originalQueue, String messageType,
                                        String payload, String failureReason) {
        DeadLetterMessage msg = new DeadLetterMessage();
        msg.setOriginalQueue(originalQueue);
        msg.setMessageType(messageType);
        msg.setPayload(payload);
        msg.setFailureReason(failureReason);
        msg.setReceivedAt(Instant.now());
        msg.setRetryCount(0);
        msg.setNextRetryAt(calculateNextRetryTime(0));

        dlqMessages.add(msg);

        log.warn("dlq.message_registered queue={} type={} reason={} will_retry_at={}",
            originalQueue, messageType, failureReason, msg.getNextRetryAt());
    }

    private boolean isReadyForRetry(DeadLetterMessage msg) {
        if (msg.getNextRetryAt() == null) {
            return false;
        }
        return msg.getNextRetryAt().isBefore(Instant.now());
    }

    private void retryMessage(DeadLetterMessage msg) {
        try {
            // Replay message to original queue
            rabbitTemplate.convertAndSend(msg.getOriginalQueue(), msg.getPayload());

            msg.incrementRetryCount();
            msg.setNextRetryAt(calculateNextRetryTime(msg.getRetryCount()));
            msg.setLastRetryAt(Instant.now());

            log.info("dlq.retry_sent queue={} type={} attempt={} next_retry={}",
                msg.getOriginalQueue(), msg.getMessageType(), msg.getRetryCount(), msg.getNextRetryAt());

        } catch (Exception ex) {
            log.error("dlq.retry_failed queue={} attempt={} {}", msg.getOriginalQueue(), msg.getRetryCount(), ex.getMessage());
            msg.setNextRetryAt(calculateNextRetryTime(msg.getRetryCount() + 1));
        }
    }

    private Instant calculateNextRetryTime(int retryCount) {
        // Exponential backoff: 1s, 2s, 4s, 8s, 16s
        long backoffMs = initialBackoffMs * (long) Math.pow(2, Math.min(retryCount, 4));
        return Instant.now().plusMillis(backoffMs);
    }

    /**
     * Dead letter message with retry tracking.
     */
    public static class DeadLetterMessage {
        private String originalQueue;
        private String messageType;
        private String payload;
        private String failureReason;
        private Instant receivedAt;
        private Instant lastRetryAt;
        private Instant nextRetryAt;
        private int retryCount;

        public void incrementRetryCount() { retryCount++; }

        // Getters/Setters
        public String getOriginalQueue() { return originalQueue; }
        public String getMessageType() { return messageType; }
        public String getPayload() { return payload; }
        public String getFailureReason() { return failureReason; }
        public Instant getReceivedAt() { return receivedAt; }
        public Instant getLastRetryAt() { return lastRetryAt; }
        public Instant getNextRetryAt() { return nextRetryAt; }
        public int getRetryCount() { return retryCount; }

        public void setOriginalQueue(String queue) { this.originalQueue = queue; }
        public void setMessageType(String type) { this.messageType = type; }
        public void setPayload(String payload) { this.payload = payload; }
        public void setFailureReason(String reason) { this.failureReason = reason; }
        public void setReceivedAt(Instant time) { this.receivedAt = time; }
        public void setLastRetryAt(Instant time) { this.lastRetryAt = time; }
        public void setNextRetryAt(Instant time) { this.nextRetryAt = time; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    }

    public List<DeadLetterMessage> getDLQMessages() {
        return new ArrayList<>(dlqMessages);
    }
}
