package com.stokr.bootstrap.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.*;

/**
 * Real service health checker that pings actual service endpoints.
 * Replaces hardcoded mock data in MicroservicesHealthController.
 *
 * This service will be integrated in Phase 2 (Week 1).
 * Currently, controllers return mock data with DEMO MODE warning.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealServiceHealthChecker {

    private final RestTemplate restTemplate;

    @Value("${strategy.service.url:http://localhost:8081}")
    private String strategyServiceUrl;

    @Value("${execution.service.url:http://localhost:8082}")
    private String executionServiceUrl;

    @Value("${risk.service.url:http://localhost:8080}")
    private String riskServiceUrl;

    @Value("${market.data.service.url:http://localhost:8080}")
    private String marketDataServiceUrl;

    /**
     * Check health of Strategy Service (port 8081)
     * Pings: /actuator/health
     * Measures response time
     */
    public ServiceHealthStatus checkStrategyService() {
        return checkService(
            "strategy-service",
            strategyServiceUrl + "/actuator/health",
            1  // Expected single instance
        );
    }

    /**
     * Check health of Execution Service (port 8082)
     * Pings: /actuator/health
     * Measures response time
     * Note: May have multiple replicas
     */
    public ServiceHealthStatus checkExecutionService() {
        return checkService(
            "execution-service",
            executionServiceUrl + "/actuator/health",
            2  // Expected 2 replicas
        );
    }

    /**
     * Check health of Risk Service (core trading module)
     * Pings: /actuator/health
     * Measures response time
     */
    public ServiceHealthStatus checkRiskService() {
        return checkService(
            "risk-service",
            riskServiceUrl + "/actuator/health",
            1
        );
    }

    /**
     * Check health of Market Data Service (core trading module)
     * Pings: /actuator/health
     * Measures response time
     */
    public ServiceHealthStatus checkMarketDataService() {
        return checkService(
            "market-data-service",
            marketDataServiceUrl + "/actuator/health",
            1
        );
    }

    /**
     * Generic service health check
     */
    private ServiceHealthStatus checkService(String serviceName, String healthUrl, int expectedInstances) {
        try {
            long startTime = System.currentTimeMillis();
            ResponseEntity<?> response = restTemplate.getForEntity(healthUrl, Map.class);
            long responseTime = System.currentTimeMillis() - startTime;

            String status = response.getStatusCode().is2xxSuccessful() ? "UP" : "DEGRADED";

            // Mark as DEGRADED if response time is high (> 1 second)
            if (responseTime > 1000) {
                status = "DEGRADED";
            }

            log.debug("Health check for {} - Status: {} ({}ms)", serviceName, status, responseTime);

            return ServiceHealthStatus.builder()
                    .name(serviceName)
                    .status(status)
                    .responseTime((int) responseTime)
                    .instances(expectedInstances)
                    .lastCheck(new Date())
                    .build();

        } catch (RestClientException e) {
            log.warn("Health check failed for {}: {}", serviceName, e.getMessage());

            return ServiceHealthStatus.builder()
                    .name(serviceName)
                    .status("DOWN")
                    .responseTime(5000)  // Timeout
                    .instances(0)
                    .lastCheck(new Date())
                    .error(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error checking health for {}", serviceName, e);

            return ServiceHealthStatus.builder()
                    .name(serviceName)
                    .status("DOWN")
                    .responseTime(5000)
                    .instances(0)
                    .lastCheck(new Date())
                    .error("Unexpected error: " + e.getMessage())
                    .build();
        }
    }

    // DTO for service health status
    @lombok.Data
    @lombok.Builder
    public static class ServiceHealthStatus {
        private String name;
        private String status;  // UP, DEGRADED, DOWN
        private int responseTime;
        private int instances;
        private Date lastCheck;
        private String error;  // Set if status is DOWN
    }
}
