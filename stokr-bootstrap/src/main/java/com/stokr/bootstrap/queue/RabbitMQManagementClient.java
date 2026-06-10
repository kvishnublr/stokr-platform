package com.stokr.bootstrap.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.*;

/**
 * RabbitMQ Management API client.
 * Connects to RabbitMQ HTTP management API to get real queue statistics.
 *
 * This service will be integrated in Phase 2 (Week 1).
 * Currently, controllers return mock data with DEMO MODE warning.
 *
 * API Reference: http://your-rabbitmq:15672/api/
 * (Default user: guest/guest)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RabbitMQManagementClient {

    private final RestTemplate restTemplate;

    @Value("${rabbitmq.management.url:http://localhost:15672}")
    private String managementUrl;

    @Value("${rabbitmq.management.username:guest}")
    private String username;

    @Value("${rabbitmq.management.password:guest}")
    private String password;

    private static final String VHOST = "%2F";  // URL-encoded /

    /**
     * Get queue status from RabbitMQ
     * Calls: GET /api/queues/%2F/{queue-name}
     */
    public QueueStatusDto getQueueStatus(String queueName) {
        try {
            String url = String.format(
                    "%s/api/queues/%s/%s",
                    managementUrl,
                    VHOST,
                    queueName
            );

            long startTime = System.currentTimeMillis();
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            long duration = System.currentTimeMillis() - startTime;

            if (response == null) {
                log.warn("No response from RabbitMQ management API for queue {}", queueName);
                return QueueStatusDto.builder()
                        .name(queueName)
                        .pending(0)
                        .consumerCount(0)
                        .processingRate(0)
                        .avgMessageAge(0)
                        .error("No response from API")
                        .build();
            }

            // Parse response
            Integer backingUpCount = (Integer) response.getOrDefault("messages_details", Map.of())
                    .getClass().getSimpleName().equals("LinkedHashMap") ? 0 : 0;

            int pending = ((Number) response.getOrDefault("messages", 0)).intValue();
            int consumerCount = ((Number) response.getOrDefault("consumers", 0)).intValue();

            // Calculate processing rate (messages per second)
            // This would be: total acked in last period / period length
            // For now, estimate from backend rates
            int processingRate = consumerCount > 0 ? 50 : 0;  // Will be calculated from real data

            double avgAge = ((Number) response.getOrDefault("idle_since", 0)).doubleValue();

            log.debug("Queue {} status - Pending: {}, Consumers: {}, Duration: {}ms",
                    queueName, pending, consumerCount, duration);

            return QueueStatusDto.builder()
                    .name(queueName)
                    .pending(pending)
                    .consumerCount(consumerCount)
                    .processingRate(processingRate)
                    .avgMessageAge(avgAge)
                    .responseTime((int) duration)
                    .deadLetterQueue(getDeadLetterQueueStatus(queueName))
                    .build();

        } catch (RestClientException e) {
            log.warn("Failed to get queue status for {}: {}", queueName, e.getMessage());
            return QueueStatusDto.builder()
                    .name(queueName)
                    .pending(0)
                    .consumerCount(0)
                    .error("Failed to connect to RabbitMQ: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get all queues status
     * Calls: GET /api/queues/%2F
     */
    public List<QueueStatusDto> getAllQueuesStatus() {
        try {
            String url = String.format("%s/api/queues/%s", managementUrl, VHOST);

            org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>> typeRef =
                    new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {};

            List<Map<String, Object>> queues = restTemplate.exchange(url,
                    org.springframework.http.HttpMethod.GET, null, typeRef).getBody();

            if (queues == null) {
                return new ArrayList<>();
            }

            List<QueueStatusDto> results = new ArrayList<>();
            for (Map<String, Object> queue : queues) {
                String name = (String) queue.get("name");
                int pending = ((Number) queue.getOrDefault("messages", 0)).intValue();
                int consumers = ((Number) queue.getOrDefault("consumers", 0)).intValue();

                results.add(QueueStatusDto.builder()
                        .name(name)
                        .pending(pending)
                        .consumerCount(consumers)
                        .processingRate(consumers > 0 ? 50 : 0)
                        .avgMessageAge(0)
                        .build());
            }

            return results;

        } catch (Exception e) {
            log.error("Failed to get all queues: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get dead-letter queue status
     */
    private DeadLetterQueueDto getDeadLetterQueueStatus(String queueName) {
        String dlqName = queueName + ".dlq";
        try {
            QueueStatusDto dlqStatus = getQueueStatus(dlqName);
            return DeadLetterQueueDto.builder()
                    .name(dlqName)
                    .pending(dlqStatus.getPending())
                    .build();
        } catch (Exception e) {
            log.warn("Could not get DLQ status for {}: {}", dlqName, e.getMessage());
            return DeadLetterQueueDto.builder()
                    .name(dlqName)
                    .pending(0)
                    .build();
        }
    }

    /**
     * Test connection to RabbitMQ management API
     */
    public boolean testConnection() {
        try {
            String url = managementUrl + "/api/overview";
            restTemplate.getForObject(url, Map.class);
            log.info("Successfully connected to RabbitMQ management API");
            return true;
        } catch (Exception e) {
            log.error("Failed to connect to RabbitMQ management API: {}", e.getMessage());
            return false;
        }
    }

    // DTOs
    @lombok.Data
    @lombok.Builder
    public static class QueueStatusDto {
        private String name;
        private int pending;
        private int consumerCount;
        private int processingRate;  // messages per second
        private double avgMessageAge;
        private int responseTime;
        private String error;
        private DeadLetterQueueDto deadLetterQueue;
    }

    @lombok.Data
    @lombok.Builder
    public static class DeadLetterQueueDto {
        private String name;
        private int pending;
    }
}
