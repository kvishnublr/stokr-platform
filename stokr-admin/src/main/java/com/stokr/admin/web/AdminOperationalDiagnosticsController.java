package com.stokr.admin.web;

import com.stokr.admin.service.AdminOperationalDiagnosticsService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/operations/diagnostics")
@RequiredArgsConstructor
@Tag(name = "Admin operational diagnostics")
public class AdminOperationalDiagnosticsController {

    private final AdminOperationalDiagnosticsService diagnosticsService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> diagnostics() {
        return ApiResponse.ok(diagnosticsService.liveDiagnostics(Instant.now()), CorrelationIdHolder.get());
    }
}
