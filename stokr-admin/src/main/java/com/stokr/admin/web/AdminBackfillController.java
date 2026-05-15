package com.stokr.admin.web;

import com.stokr.admin.service.AdminBackfillService;
import com.stokr.backtest.domain.BacktestJob;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/backfill")
@RequiredArgsConstructor
public class AdminBackfillController {

    private final AdminBackfillService adminBackfillService;

    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> jobs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = adminBackfillService.recentJobs(limit).stream().map(this::toRow).toList();
        return ApiResponse.ok(rows, CorrelationIdHolder.get());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> cancel(@PathVariable("jobId") UUID jobId) {
        adminBackfillService.cancel(jobId);
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    @PostMapping("/jobs/{jobId}/rerun")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> rerun(@PathVariable("jobId") UUID jobId) {
        UUID id = adminBackfillService.rerun(jobId);
        return ApiResponse.ok(Map.of("jobId", id.toString()), CorrelationIdHolder.get());
    }

    private Map<String, Object> toRow(BacktestJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId().toString());
        m.put("status", j.getStatus().name());
        m.put("progress", j.getProgress());
        m.put("processedBars", j.getProcessedBars());
        m.put("totalBars", j.getTotalBars());
        m.put("userId", j.getUserId() != null ? j.getUserId().toString() : null);
        m.put("message", j.getMessage());
        m.put("updatedAt", j.getUpdatedAt() != null ? j.getUpdatedAt().toString() : null);
        m.put("createdAt", j.getCreatedAt() != null ? j.getCreatedAt().toString() : null);
        m.put("runId", j.getRunId() != null ? j.getRunId().toString() : null);
        m.put("replayDiagnosis", j.getReplayDiagnosis());
        return m;
    }
}

