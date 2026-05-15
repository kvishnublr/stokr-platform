package com.stokr.admin.web;

import com.stokr.admin.dto.MarketBackfillCreateRequest;
import com.stokr.admin.service.AdminMarketBackfillService;
import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/market/backfill")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMarketBackfillController {

    private final AdminMarketBackfillService backfillService;

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        return ApiResponse.ok(backfillService.capabilityMatrix(), CorrelationIdHolder.get());
    }

    @PostMapping("/jobs")
    public ApiResponse<Map<String, Object>> create(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody MarketBackfillCreateRequest body
    ) {
        UUID id = backfillService.createAndStart(user.getId(), body);
        return ApiResponse.ok(Map.of("jobId", id.toString()), CorrelationIdHolder.get());
    }

    @GetMapping("/jobs")
    public ApiResponse<Object> jobs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(backfillService.listJobs(limit), CorrelationIdHolder.get());
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<Map<String, Object>> job(@PathVariable("jobId") UUID jobId) {
        return ApiResponse.ok(backfillService.jobDetail(jobId), CorrelationIdHolder.get());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable("jobId") UUID jobId) {
        backfillService.cancel(jobId);
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    @PostMapping("/jobs/{jobId}/retry-failures")
    public ApiResponse<Map<String, Object>> retryFailures(@PathVariable("jobId") UUID jobId) {
        UUID id = backfillService.retryFailures(jobId);
        return ApiResponse.ok(Map.of("jobId", id.toString()), CorrelationIdHolder.get());
    }

    @PostMapping("/jobs/{jobId}/repair-gaps")
    public ApiResponse<Map<String, Object>> repairGaps(@PathVariable("jobId") UUID jobId) {
        UUID id = backfillService.repairGaps(jobId);
        return ApiResponse.ok(Map.of("jobId", id.toString()), CorrelationIdHolder.get());
    }
}
