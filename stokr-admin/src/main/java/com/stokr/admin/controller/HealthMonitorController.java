package com.stokr.admin.controller;

import com.stokr.common.health.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin API for monitoring health of all microservices, queues, and infrastructure.
 *
 * Endpoints:
 * GET  /api/v1/admin/health               - Overall system health
 * GET  /api/v1/admin/health/services      - Service health only
 * GET  /api/v1/admin/health/queues        - Queue health only
 * GET  /api/v1/admin/health/infrastructure - Infrastructure health only
 */
@RestController
@RequestMapping("/api/v1/admin/health")
@RequiredArgsConstructor
@Slf4j
public class HealthMonitorController {

    private final HealthMonitoringService healthMonitoringService;

    @GetMapping
    public ResponseEntity<HealthResponse> getSystemHealth() {
        log.info("GET /health - System health check requested");
        HealthResponse health = healthMonitoringService.getSystemHealth();
        return ResponseEntity.ok(health);
    }

    @GetMapping("/services")
    public ResponseEntity<List<ServiceHealth>> getServicesHealth() {
        log.info("GET /health/services - Services health check requested");
        List<ServiceHealth> services = healthMonitoringService.getServicesHealth();
        return ResponseEntity.ok(services);
    }

    @GetMapping("/services/{serviceName}")
    public ResponseEntity<ServiceHealth> getServiceHealth(@PathVariable String serviceName) {
        log.info("GET /health/services/{} - Service health check requested", serviceName);
        ServiceHealth service = healthMonitoringService.getServiceHealth(serviceName);
        return ResponseEntity.ok(service);
    }

    @GetMapping("/queues")
    public ResponseEntity<List<QueueHealth>> getQueuesHealth() {
        log.info("GET /health/queues - Queues health check requested");
        List<QueueHealth> queues = healthMonitoringService.getQueuesHealth();
        return ResponseEntity.ok(queues);
    }

    @GetMapping("/queues/{queueName}")
    public ResponseEntity<QueueHealth> getQueueHealth(@PathVariable String queueName) {
        log.info("GET /health/queues/{} - Queue health check requested", queueName);
        QueueHealth queue = healthMonitoringService.getQueueHealth(queueName);
        return ResponseEntity.ok(queue);
    }

    @GetMapping("/infrastructure")
    public ResponseEntity<HealthResponse.InfrastructureHealth> getInfrastructureHealth() {
        log.info("GET /health/infrastructure - Infrastructure health check requested");
        HealthResponse.InfrastructureHealth infrastructure = healthMonitoringService.getInfrastructureHealth();
        return ResponseEntity.ok(infrastructure);
    }
}
