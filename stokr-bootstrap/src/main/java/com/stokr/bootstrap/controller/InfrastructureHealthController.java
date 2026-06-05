package com.stokr.bootstrap.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class InfrastructureHealthController {

    @GetMapping("/infrastructure-health")
    public String showInfrastructureHealth() {
        return "forward:/infrastructure-health.html";
    }

    @RestController
    @RequestMapping("/api/infrastructure")
    public static class InfrastructureHealthApi {

        @GetMapping("/health-snapshot")
        public Map<String, Object> getHealthSnapshot() {
            Map<String, Object> response = new LinkedHashMap<>();

            // Overall status
            response.put("timestamp", Instant.now());
            response.put("overall_status", "HEALTHY");
            response.put("critical_issues", 0);
            response.put("warnings", 1);

            // Components status
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("market_feed", buildComponent("Market Feed", "CLOSED", "-", "warning"));
            components.put("oms", buildComponent("OMS", "READY", "37.80%", "healthy"));
            components.put("redis", buildComponent("Redis", "CONNECTED", "15ms", "healthy"));
            components.put("rabbitmq", buildComponent("RabbitMQ", "CONNECTED", "12ms", "healthy"));
            components.put("postgresql", buildComponent("PostgreSQL", "CONNECTED", "8ms", "healthy"));
            components.put("signal_engine", buildComponent("Signal Engine", "RUNNING", "active", "healthy"));
            components.put("broker_rail", buildComponent("Broker Rail", "CONNECTED", "45ms", "healthy"));
            components.put("kill_switch", buildComponent("Kill Switch", "ARMED", "active", "warning"));

            response.put("components", components);

            // System metrics
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("cpu_usage", 34);
            metrics.put("memory_usage", 62);
            metrics.put("disk_io", 28);
            metrics.put("network_usage", 18);
            response.put("metrics", metrics);

            return response;
        }

        @GetMapping("/components")
        public Map<String, Object> getComponentsDetailed() {
            Map<String, Object> response = new LinkedHashMap<>();
            List<Map<String, Object>> components = new ArrayList<>();

            components.add(buildDetailedComponent("Market Feed", "CLOSED", "warning",
                "Market session is closed", "13:35", "Market hours violation"));
            components.add(buildDetailedComponent("OMS", "READY", "healthy",
                "Order Management System operational", "13:20", "Load: 37.80%"));
            components.add(buildDetailedComponent("Redis", "CONNECTED", "healthy",
                "Cache layer connected", "13:10", "Latency: 15ms, 0 errors"));
            components.add(buildDetailedComponent("RabbitMQ", "CONNECTED", "healthy",
                "Message queue operational", "13:05", "Processing normally"));
            components.add(buildDetailedComponent("PostgreSQL", "CONNECTED", "healthy",
                "Database connected", "13:00", "Response time: 8ms"));
            components.add(buildDetailedComponent("Signal Engine", "RUNNING", "healthy",
                "Trading signals active", "12:55", "11 running instances"));
            components.add(buildDetailedComponent("Broker Rail", "CONNECTED", "healthy",
                "Broker connection stable", "12:50", "Latency: 45ms"));
            components.add(buildDetailedComponent("Kill Switch", "ARMED", "warning",
                "Emergency stop armed", "12:45", "Global halt active"));

            response.put("components", components);
            response.put("count", components.size());
            response.put("healthy_count", (int) components.stream()
                .filter(c -> "healthy".equals(c.get("status"))).count());
            response.put("warning_count", (int) components.stream()
                .filter(c -> "warning".equals(c.get("status"))).count());
            response.put("critical_count", (int) components.stream()
                .filter(c -> "critical".equals(c.get("status"))).count());

            return response;
        }

        @GetMapping("/issues")
        public Map<String, Object> getIssues() {
            Map<String, Object> response = new LinkedHashMap<>();
            List<Map<String, Object>> issues = new ArrayList<>();

            issues.add(buildIssue("WARNING", "Market Feed CLOSED", "Trading halted - market hours violation", "13:35"));
            issues.add(buildIssue("INFO", "Kill Switch ARMED", "Emergency stop activated - execution plane hot", "13:30"));
            issues.add(buildIssue("INFO", "OMS Load 37.80%", "Monitoring increased - load threshold approaching", "13:20"));

            response.put("issues", issues);
            response.put("critical_count", (int) issues.stream()
                .filter(i -> "CRITICAL".equals(i.get("severity"))).count());
            response.put("warning_count", (int) issues.stream()
                .filter(i -> "WARNING".equals(i.get("severity"))).count());
            response.put("info_count", (int) issues.stream()
                .filter(i -> "INFO".equals(i.get("severity"))).count());

            return response;
        }

        @GetMapping("/timeline")
        public Map<String, Object> getTimeline() {
            Map<String, Object> response = new LinkedHashMap<>();
            List<Map<String, Object>> events = new ArrayList<>();

            events.add(buildTimelineEvent("13:35", "Market Feed closed - automatic halt triggered", false));
            events.add(buildTimelineEvent("13:30", "Kill Switch activated - safety mechanism engaged", false));
            events.add(buildTimelineEvent("13:20", "OMS load at 37.80% - monitoring increased", false));
            events.add(buildTimelineEvent("13:10", "Redis connection stable - 15ms latency", false));
            events.add(buildTimelineEvent("13:05", "RabbitMQ processing 45,234 messages", false));
            events.add(buildTimelineEvent("13:00", "All systems nominal - all components connected", false));
            events.add(buildTimelineEvent("12:55", "Signal Engine fired 234 signals", false));
            events.add(buildTimelineEvent("12:50", "Broker Rail latency optimized to 45ms", false));

            response.put("events", events);
            response.put("total_events", events.size());

            return response;
        }

        @GetMapping("/metrics/detailed")
        public Map<String, Object> getDetailedMetrics() {
            Map<String, Object> response = new LinkedHashMap<>();

            // System metrics
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("cpu_usage", buildMetric(34, "%", "healthy"));
            system.put("memory_usage", buildMetric(62, "%", "warning"));
            system.put("disk_io", buildMetric(28, "%", "healthy"));
            system.put("network_usage", buildMetric(18, "%", "healthy"));
            response.put("system", system);

            // Performance metrics
            Map<String, Object> performance = new LinkedHashMap<>();
            performance.put("request_latency_avg", buildMetric(245, "ms", "healthy"));
            performance.put("request_latency_p99", buildMetric(890, "ms", "warning"));
            performance.put("error_rate", buildMetric(0.02, "%", "healthy"));
            performance.put("success_rate", buildMetric(99.98, "%", "healthy"));
            response.put("performance", performance);

            // Database metrics
            Map<String, Object> database = new LinkedHashMap<>();
            database.put("connection_pool_usage", buildMetric(45, "%", "healthy"));
            database.put("active_connections", buildMetric(9, "conn", "healthy"));
            database.put("max_connections", buildMetric(20, "conn", "healthy"));
            database.put("query_time_avg", buildMetric(8, "ms", "healthy"));
            response.put("database", database);

            // Cache metrics
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("hit_rate", buildMetric(87.5, "%", "healthy"));
            cache.put("miss_rate", buildMetric(12.5, "%", "healthy"));
            cache.put("eviction_rate", buildMetric(0.5, "%", "healthy"));
            cache.put("memory_used", buildMetric(512, "MB", "healthy"));
            response.put("cache", cache);

            return response;
        }

        // Helper methods
        private Map<String, Object> buildComponent(String name, String status, String latency, String health) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("name", name);
            comp.put("status", status);
            comp.put("latency", latency);
            comp.put("health", health);
            comp.put("last_check", Instant.now());
            return comp;
        }

        private Map<String, Object> buildDetailedComponent(String name, String status, String health,
                                                           String description, String lastCheck, String details) {
            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("name", name);
            comp.put("status", status);
            comp.put("health", health);
            comp.put("description", description);
            comp.put("last_check", lastCheck);
            comp.put("details", details);
            comp.put("uptime_percent", 99.95);
            return comp;
        }

        private Map<String, Object> buildIssue(String severity, String title, String description, String time) {
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("severity", severity);
            issue.put("title", title);
            issue.put("description", description);
            issue.put("time", time);
            issue.put("resolved", false);
            return issue;
        }

        private Map<String, Object> buildTimelineEvent(String time, String text, boolean critical) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("time", time);
            event.put("text", text);
            event.put("critical", critical);
            event.put("timestamp", Instant.now());
            return event;
        }

        private Map<String, Object> buildMetric(Object value, String unit, String status) {
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("value", value);
            metric.put("unit", unit);
            metric.put("status", status);
            return metric;
        }
    }
}
