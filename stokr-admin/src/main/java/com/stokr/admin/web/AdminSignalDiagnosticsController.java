package com.stokr.admin.web;

import com.stokr.admin.signal.AdminProtectionDiagnosticsDto;
import com.stokr.admin.signal.AdminSignalTruthDiagnosticsService;
import com.stokr.admin.signal.AdminStrategyDiagnosticsDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/diagnostics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin signal truth diagnostics")
public class AdminSignalDiagnosticsController {

    private final AdminSignalTruthDiagnosticsService diagnosticsService;

    @GetMapping("/protection")
    @Operation(summary = "Protection exit diagnostics from persisted telemetry")
    public ApiResponse<AdminProtectionDiagnosticsDto> protection(
            @RequestParam(required = false) Instant since
    ) {
        return ApiResponse.ok(diagnosticsService.protectionDiagnostics(since), CorrelationIdHolder.get());
    }

    @GetMapping("/strategy")
    @Operation(summary = "Production signal confidence and lifecycle diagnostics")
    public ApiResponse<AdminStrategyDiagnosticsDto> strategy(
            @RequestParam(required = false) Instant since
    ) {
        return ApiResponse.ok(diagnosticsService.strategyDiagnostics(since), CorrelationIdHolder.get());
    }
}
