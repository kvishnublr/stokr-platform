package com.stokr.admin.web;

import com.stokr.common.exception.BadRequestException;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/backfill")
@RequiredArgsConstructor
public class AdminBackfillController {

    private static final String DEPRECATION_NOTE =
            "Deprecated endpoint. Use /api/admin/market/backfill/* for historical ingestion and /api/admin/replay/* for replay jobs.";

    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> jobs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        throw new BadRequestException(DEPRECATION_NOTE);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> cancel(@PathVariable("jobId") UUID jobId) {
        throw new BadRequestException(DEPRECATION_NOTE);
    }

    @PostMapping("/jobs/{jobId}/rerun")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> rerun(@PathVariable("jobId") UUID jobId) {
        throw new BadRequestException(DEPRECATION_NOTE);
    }
}
