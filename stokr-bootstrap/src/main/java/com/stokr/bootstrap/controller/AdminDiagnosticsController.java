package com.stokr.bootstrap.controller;

import com.stokr.bootstrap.service.AdminHealthDashboard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/diagnostics")
@RequiredArgsConstructor
public class AdminDiagnosticsController {
    private static final Logger log = LoggerFactory.getLogger(AdminDiagnosticsController.class);

    private final AdminHealthDashboard dashboard;

    @GetMapping("/health")
    public ResponseEntity<AdminHealthDashboard.SystemHealthSnapshot> getCurrentHealth() {
        log.info("Admin requesting current system health snapshot");
        return ResponseEntity.ok(dashboard.getCurrentHealth());
    }

    @GetMapping("/timeline")
    public ResponseEntity<List<AdminHealthDashboard.IssueTimeline>> getIssueTimeline(
            @RequestParam(defaultValue = "24") int lastHours) {
        log.info("Admin requesting issue timeline for last {} hours", lastHours);
        return ResponseEntity.ok(dashboard.getIssueTimeline(lastHours));
    }

    @GetMapping("/component-status")
    public ResponseEntity<Map<String, AdminHealthDashboard.HealthStatus>> getComponentStatus() {
        log.info("Admin requesting component status");
        return ResponseEntity.ok(dashboard.getComponentStatus());
    }

    @GetMapping("/diagnose")
    public ResponseEntity<AdminHealthDashboard.SystemDiagnosis> diagnoseIssue(
            @RequestParam String issueType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime when) {
        log.info("Admin requesting diagnosis for {} at {}", issueType, when);
        return ResponseEntity.ok(dashboard.diagnoseIssue(issueType, when));
    }

    @GetMapping("/root-cause")
    public ResponseEntity<AdminHealthDashboard.IssueAnalysis> analyzeRootCause(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        log.info("Admin requesting root cause analysis from {} to {}", startTime, endTime);
        return ResponseEntity.ok(dashboard.analyzeRootCause(startTime, endTime));
    }

    @GetMapping("/quick-summary")
    public ResponseEntity<QuickSummary> getQuickSummary() {
        var health = dashboard.getCurrentHealth();
        var timeline = dashboard.getIssueTimeline(1);  // Last 1 hour
        var components = dashboard.getComponentStatus();

        var summary = QuickSummary.builder()
            .timestamp(health.getTimestamp())
            .overallStatus(health.getOverallStatus())
            .activeIssues(timeline.size())
            .criticalIssues((int) timeline.stream()
                .filter(t -> "CRITICAL".equals(t.getSeverity()))
                .count())
            .components(components)
            .build();

        return ResponseEntity.ok(summary);
    }

    @GetMapping("/alert-summary")
    public ResponseEntity<AlertSummary> getAlertSummary(
            @RequestParam(defaultValue = "1") int lastHours) {
        log.info("Admin requesting alert summary for last {} hours", lastHours);

        var timeline = dashboard.getIssueTimeline(lastHours);

        var byCategory = timeline.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                AdminHealthDashboard.IssueTimeline::getCategory,
                java.util.stream.Collectors.counting()
            ));

        var bySeverity = timeline.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                AdminHealthDashboard.IssueTimeline::getSeverity,
                java.util.stream.Collectors.counting()
            ));

        var autoFixedCount = timeline.stream()
            .filter(t -> t.getHasAutoFix() && t.getAutoFixSuccessful())
            .count();

        var summary = AlertSummary.builder()
            .timeRange(lastHours + " hours")
            .totalIssues(timeline.size())
            .issuesByCategory(byCategory)
            .issuesBySeverity(bySeverity)
            .autoFixedCount((int) autoFixedCount)
            .topIssue(timeline.isEmpty() ? null : timeline.get(0))
            .build();

        return ResponseEntity.ok(summary);
    }

    // ============ DTOs ============

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QuickSummary {
        private LocalDateTime timestamp;
        private String overallStatus;
        private int activeIssues;
        private int criticalIssues;
        private Map<String, AdminHealthDashboard.HealthStatus> components;

        // Explicit builder method (Lombok not generating)
        public static QuickSummaryBuilder builder() {
            return new QuickSummaryBuilder();
        }

        public static class QuickSummaryBuilder {
            private LocalDateTime timestamp;
            private String overallStatus;
            private int activeIssues;
            private int criticalIssues;
            private Map<String, AdminHealthDashboard.HealthStatus> components;

            public QuickSummaryBuilder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
            public QuickSummaryBuilder overallStatus(String overallStatus) { this.overallStatus = overallStatus; return this; }
            public QuickSummaryBuilder activeIssues(int activeIssues) { this.activeIssues = activeIssues; return this; }
            public QuickSummaryBuilder criticalIssues(int criticalIssues) { this.criticalIssues = criticalIssues; return this; }
            public QuickSummaryBuilder components(Map<String, AdminHealthDashboard.HealthStatus> components) { this.components = components; return this; }
            public QuickSummary build() {
                return new QuickSummary(timestamp, overallStatus, activeIssues, criticalIssues, components);
            }
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AlertSummary {
        private String timeRange;
        private int totalIssues;
        private Map<String, Long> issuesByCategory;
        private Map<String, Long> issuesBySeverity;
        private int autoFixedCount;
        private AdminHealthDashboard.IssueTimeline topIssue;

        // Explicit builder method (Lombok not generating)
        public static AlertSummaryBuilder builder() {
            return new AlertSummaryBuilder();
        }

        public static class AlertSummaryBuilder {
            private String timeRange;
            private int totalIssues;
            private Map<String, Long> issuesByCategory;
            private Map<String, Long> issuesBySeverity;
            private int autoFixedCount;
            private AdminHealthDashboard.IssueTimeline topIssue;

            public AlertSummaryBuilder timeRange(String timeRange) { this.timeRange = timeRange; return this; }
            public AlertSummaryBuilder totalIssues(int totalIssues) { this.totalIssues = totalIssues; return this; }
            public AlertSummaryBuilder issuesByCategory(Map<String, Long> issuesByCategory) { this.issuesByCategory = issuesByCategory; return this; }
            public AlertSummaryBuilder issuesBySeverity(Map<String, Long> issuesBySeverity) { this.issuesBySeverity = issuesBySeverity; return this; }
            public AlertSummaryBuilder autoFixedCount(int autoFixedCount) { this.autoFixedCount = autoFixedCount; return this; }
            public AlertSummaryBuilder topIssue(AdminHealthDashboard.IssueTimeline topIssue) { this.topIssue = topIssue; return this; }
            public AlertSummary build() {
                return new AlertSummary(timeRange, totalIssues, issuesByCategory, issuesBySeverity, autoFixedCount, topIssue);
            }
        }
    }
}
