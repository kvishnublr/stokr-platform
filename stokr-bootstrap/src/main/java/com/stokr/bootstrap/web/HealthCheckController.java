package com.stokr.bootstrap.web;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthCheckController {

    private final DataSource dataSource;
    private static final Instant APP_START_TIME = Instant.now();

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("uptime_seconds", Instant.now().getEpochSecond() - APP_START_TIME.getEpochSecond());

        // Check database connectivity
        Map<String, Object> dbStatus = checkDatabase();
        response.put("database", dbStatus);

        // Overall status
        boolean isHealthy = "UP".equals(dbStatus.get("status"));
        if (isHealthy) {
            response.put("livenessState", "LIVE");
            response.put("readinessState", "READY");
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "DEGRADED");
            response.put("livenessState", "LIVE");
            response.put("readinessState", "NOT_READY");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> dbInfo = new LinkedHashMap<>();

        try {
            // Test connection
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(5)) {
                    dbInfo.put("status", "UP");
                    dbInfo.put("connection", "OK");

                    // Get pool info if available
                    if (dataSource instanceof HikariDataSource) {
                        HikariDataSource hikari = (HikariDataSource) dataSource;
                        dbInfo.put("pool_active", hikari.getHikariPoolMXBean().getActiveConnections());
                        dbInfo.put("pool_idle", hikari.getHikariPoolMXBean().getIdleConnections());
                        dbInfo.put("pool_total", hikari.getHikariPoolMXBean().getTotalConnections());
                    }
                } else {
                    dbInfo.put("status", "DOWN");
                    dbInfo.put("connection", "INVALID");
                }
            }
        } catch (Exception e) {
            log.warn("Database health check failed", e);
            dbInfo.put("status", "DOWN");
            dbInfo.put("connection", "ERROR");
            dbInfo.put("error", e.getMessage());
        }

        return dbInfo;
    }
}
