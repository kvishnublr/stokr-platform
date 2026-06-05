package com.stokr.bootstrap.controller;

import com.stokr.bootstrap.service.AdminHealthDashboard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/diagnostics")
@RequiredArgsConstructor
@Slf4j
public class AdminDiagnosticsController {

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
    public static class QuickSummary {
        private LocalDateTime timestamp;
        private String overallStatus;
        private int activeIssues;
        private int criticalIssues;
        private Map<String, AdminHealthDashboard.HealthStatus> components;
    }

    @lombok.Data
    @lombok.Builder
    public static class AlertSummary {
        private String timeRange;
        private int totalIssues;
        private Map<String, Long> issuesByCategory;
        private Map<String, Long> issuesBySeverity;
        private int autoFixedCount;
        private AdminHealthDashboard.IssueTimeline topIssue;
    }
}
