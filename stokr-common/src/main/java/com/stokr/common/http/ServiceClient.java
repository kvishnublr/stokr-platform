package com.stokr.common.http;

import com.stokr.common.correlation.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;

/**
 * Service for making synchronous HTTP calls to other microservices.
 * Handles correlation IDs, timeouts, and error handling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceClient {
    private final RestTemplate restTemplate;

    public <T> ResponseEntity<T> get(String url, Class<T> responseType) {
        return get(url, responseType, Duration.ofSeconds(5)); // Default 5 second timeout
    }

    public <T> ResponseEntity<T> get(String url, Class<T> responseType, Duration timeout) {
        Instant start = Instant.now();
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<T> response = restTemplate.getForEntity(url, responseType);

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.debug("GET {} completed in {}ms | status: {}", url, durationMs, response.getStatusCode());

            return response;
        } catch (RestClientException e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.error("GET {} failed after {}ms | error: {}", url, durationMs, e.getMessage());
            throw new ServiceCallException("Failed to call service: " + url, e);
        }
    }

    public <T> ResponseEntity<T> post(String url, Object request, Class<T> responseType) {
        return post(url, request, responseType, Duration.ofSeconds(5));
    }

    public <T> ResponseEntity<T> post(String url, Object request, Class<T> responseType, Duration timeout) {
        Instant start = Instant.now();
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(request, headers);

            ResponseEntity<T> response = restTemplate.postForEntity(url, entity, responseType);

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.debug("POST {} completed in {}ms | status: {}", url, durationMs, response.getStatusCode());

            return response;
        } catch (RestClientException e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.error("POST {} failed after {}ms | error: {}", url, durationMs, e.getMessage());
            throw new ServiceCallException("Failed to call service: " + url, e);
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        // Add correlation ID if available
        String correlationId = CorrelationIdHolder.getCorrelationId();
        if (correlationId != null) {
            headers.set("X-Correlation-ID", correlationId);
        }

        return headers;
    }

    /**
     * Exception thrown when inter-service HTTP call fails.
     */
    public static class ServiceCallException extends RuntimeException {
        public ServiceCallException(String message) {
            super(message);
        }

        public ServiceCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
