package com.stokr.admin.service;

import com.stokr.common.health.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.util.*;

/**
 * Service for monitoring health of microservices, queues, and infrastructure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthMonitoringService {

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final Optional<BuildProperties> buildProperties;

    public HealthResponse getSystemHealth() {
        try {
            List<ServiceHealth> services = getServicesHealth();
            List<QueueHealth> queues = getQueuesHealth();
            HealthResponse.InfrastructureHealth infrastructure = getInfrastructureHealth();
            List<HealthResponse.Alert> alerts = generateAlerts(services, queues, infrastructure);

            String overallStatus = determineOverallStatus(services, queues, infrastructure);

            return HealthResponse.builder()
                    .overallStatus(overallStatus)
                    .timestamp(Instant.now())
                    .services(services)
                    .queues(queues)
                    .infrastructure(infrastructure)
                    .alerts(alerts)
                    .systemVersion(buildProperties.map(BuildProperties::getVersion).orElse("unknown"))
                    .uptime(ManagementFactory.getRuntimeMXBean().getUptime())
                    .build();
        } catch (Exception e) {
            log.error("Error getting system health", e);
            return HealthResponse.builder()
                    .overallStatus("DOWN")
                    .timestamp(Instant.now())
                    .alerts(List.of(
                            HealthResponse.Alert.builder()
                                    .severity("CRITICAL")
                                    .message("Failed to retrieve system health: " + e.getMessage())
                                    .detectedAt(Instant.now())
                                    .build()
                    ))
                    .build();
        }
    }

    public List<ServiceHealth> getServicesHealth() {
        List<ServiceHealth> services = new ArrayList<>();

        // Check Strategy Service
        services.add(checkStrategyService());

        // Check Execution Service
        services.add(checkExecutionService());

        // Check Risk Service
        services.add(checkRiskService());

        // Check Market Data Service
        services.add(checkMarketDataService());

        // Check WebSocket Service
        services.add(checkWebSocketService());

        return services;
    }

    public ServiceHealth getServiceHealth(String serviceName) {
        return switch (serviceName.toLowerCase()) {
            case "strategy" -> checkStrategyService();
            case "execution" -> checkExecutionService();
            case "risk" -> checkRiskService();
            case "market-data" -> checkMarketDataService();
            case "websocket" -> checkWebSocketService();
            default -> ServiceHealth.builder()
                    .serviceName(serviceName)
                    .status("UNKNOWN")
                    .lastHealthCheck(Instant.now())
                    .details("Service not found")
                    .build();
        };
    }

    public List<QueueHealth> getQueuesHealth() {
        List<QueueHealth> queues = new ArrayList<>();

        try {
            // Check each trading queue
            queues.add(checkQueueHealth("trading.signals"));
            queues.add(checkQueueHealth("trading.orders"));
            queues.add(checkQueueHealth("trading.exits"));
            queues.add(checkQueueHealth("trading.audit"));

            // Check DLQs
            queues.add(checkQueueHealth("trading.signals.dlq"));
            queues.add(checkQueueHealth("trading.orders.dlq"));
            queues.add(checkQueueHealth("trading.exits.dlq"));
        } catch (Exception e) {
            log.error("Error checking queue health", e);
        }

        return queues;
    }

    public QueueHealth getQueueHealth(String queueName) {
        try {
            return checkQueueHealth(queueName);
        } catch (Exception e) {
            log.error("Error checking queue health for {}", queueName, e);
            return QueueHealth.builder()
                    .queueName(queueName)
                    .status("UNKNOWN")
                    .lastUpdate(Instant.now())
                    .alertMessage("Failed to check queue health: " + e.getMessage())
                    .build();
        }
    }

    public HealthResponse.InfrastructureHealth getInfrastructureHealth() {
        return HealthResponse.InfrastructureHealth.builder()
                .databaseStatus(checkDatabaseHealth() ? "UP" : "DOWN")
                .databaseResponseTimeMs(getDatabaseResponseTime())
                .rabbitmqStatus(checkRabbitMQHealth() ? "UP" : "DOWN")
                .rabbitmqResponseTimeMs(getRabbitMQResponseTime())
                .cpuUsage(getCPUUsage())
                .memoryUsage(getMemoryUsage())
                .diskUsage(0.0) // Would need OS-specific checks
                .build();
    }

    // ============ Private helpers ============

    private ServiceHealth checkStrategyService() {
        // In production, call the actual strategy service to check health
        // For now, return mock data
        return ServiceHealth.builder()
                .serviceName("strategy-service")
                .status("UP")
                .instances(1)
                .responseTimeMs(45L)
                .lastHealthCheck(Instant.now())
                .build();
    }

    private ServiceHealth checkExecutionService() {
        return ServiceHealth.builder()
                .serviceName("execution-service")
                .status("UP")
                .instances(2)
                .responseTimeMs(78L)
                .lastHealthCheck(Instant.now())
                .processingQueueDepth(0)
                .build();
    }

    private ServiceHealth checkRiskService() {
        return ServiceHealth.builder()
                .serviceName("risk-service")
                .status("UP")
                .instances(1)
                .responseTimeMs(12L)
                .lastHealthCheck(Instant.now())
                .build();
    }

    private ServiceHealth checkMarketDataService() {
        return ServiceHealth.builder()
                .serviceName("market-data-service")
                .status("UP")
                .instances(1)
                .responseTimeMs(23L)
                .lastHealthCheck(Instant.now())
                .build();
    }

    private ServiceHealth checkWebSocketService() {
        return ServiceHealth.builder()
                .serviceName("websocket-service")
                .status("UP")
                .instances(1)
                .responseTimeMs(5L)
                .lastHealthCheck(Instant.now())
                .activeConnections(0) // Would be fetched from actual service
                .build();
    }

    private QueueHealth checkQueueHealth(String queueName) {
        // In production, query RabbitMQ management API
        // For now, return mock data
        return QueueHealth.builder()
                .queueName(queueName)
                .status("HEALTHY")
                .pendingMessages(0L)
                .consumerCount(1)
                .processingRatePerSec(50.0)
                .avgMessageAgeMs(100L)
                .estimatedClearTimeSeconds(0L)
                .maxQueueLength(10000L)
                .isBackingUp(false)
                .deadLetterCount(0L)
                .lastUpdate(Instant.now())
                .build();
    }

    private boolean checkDatabaseHealth() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.error("Database health check failed", e);
            return false;
        }
    }

    private Long getDatabaseResponseTime() {
        try {
            long start = System.currentTimeMillis();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1L;
        }
    }

    private boolean checkRabbitMQHealth() {
        try {
            rabbitTemplate.execute(channel -> {
                channel.queueDeclarePassive("trading.signals");
                return true;
            });
            return true;
        } catch (Exception e) {
            log.error("RabbitMQ health check failed", e);
            return false;
        }
    }

    private Long getRabbitMQResponseTime() {
        try {
            long start = System.currentTimeMillis();
            rabbitTemplate.execute(channel -> {
                channel.queueDeclarePassive("trading.signals");
                return true;
            });
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1L;
        }
    }

    private Double getCPUUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double usage = osBean.getProcessCpuLoad() * 100;
        return usage > 0 ? usage : 0.0;
    }

    private Double getMemoryUsage() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long used = memoryBean.getHeapMemoryUsage().getUsed();
        long max = memoryBean.getHeapMemoryUsage().getMax();
        return (double) used / max * 100;
    }

    private String determineOverallStatus(
            List<ServiceHealth> services,
            List<QueueHealth> queues,
            HealthResponse.InfrastructureHealth infrastructure) {
        // If any critical service is down, overall status is DOWN
        boolean anyServiceDown = services.stream().anyMatch(s -> "DOWN".equals(s.getStatus()));
        if (anyServiceDown) return "DOWN";

        // If database or RabbitMQ is down, overall status is DOWN
        if ("DOWN".equals(infrastructure.getDatabaseStatus()) || "DOWN".equals(infrastructure.getRabbitmqStatus())) {
            return "DOWN";
        }

        // If any queue is backing up or critical, overall status is DEGRADED
        boolean anyQueueWarning = queues.stream().anyMatch(q -> "WARNING".equals(q.getStatus()) || "CRITICAL".equals(q.getStatus()));
        if (anyQueueWarning) return "DEGRADED";

        // If any service is degraded, overall status is DEGRADED
        boolean anyServiceDegraded = services.stream().anyMatch(s -> "DEGRADED".equals(s.getStatus()));
        if (anyServiceDegraded) return "DEGRADED";

        return "UP";
    }

    private List<HealthResponse.Alert> generateAlerts(
            List<ServiceHealth> services,
            List<QueueHealth> queues,
            HealthResponse.InfrastructureHealth infrastructure) {
        List<HealthResponse.Alert> alerts = new ArrayList<>();

        // Service alerts
        for (ServiceHealth service : services) {
            if ("DOWN".equals(service.getStatus())) {
                alerts.add(HealthResponse.Alert.builder()
                        .severity("CRITICAL")
                        .message(service.getServiceName() + " is DOWN")
                        .affectedComponent(service.getServiceName())
                        .detectedAt(Instant.now())
                        .build());
            } else if ("DEGRADED".equals(service.getStatus())) {
                alerts.add(HealthResponse.Alert.builder()
                        .severity("WARNING")
                        .message(service.getServiceName() + " is DEGRADED: " + service.getDetails())
                        .affectedComponent(service.getServiceName())
                        .detectedAt(Instant.now())
                        .build());
            }
        }

        // Queue alerts
        for (QueueHealth queue : queues) {
            if (queue.getIsBackingUp() != null && queue.getIsBackingUp()) {
                alerts.add(HealthResponse.Alert.builder()
                        .severity("WARNING")
                        .message("Queue " + queue.getQueueName() + " is backing up: " + queue.getAlertMessage())
                        .affectedComponent(queue.getQueueName())
                        .detectedAt(Instant.now())
                        .build());
            }
        }

        return alerts;
    }
}
