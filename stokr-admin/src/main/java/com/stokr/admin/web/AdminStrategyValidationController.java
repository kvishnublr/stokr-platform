package com.stokr.admin.web;

import com.stokr.admin.service.AdminStrategyValidationDiagnosticsService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/strategy-validation")
@RequiredArgsConstructor
@Tag(name = "Admin strategy validation")
public class AdminStrategyValidationController {

    private final AdminStrategyValidationDiagnosticsService diagnosticsService;

    @GetMapping("/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> diagnostics() {
        return ApiResponse.ok(diagnosticsService.diagnostics(), CorrelationIdHolder.get());
    }
}
