package com.stokr.admin.web;

import com.stokr.admin.service.AdminTradeReconciliationDiagnosticsService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/trade-reconciliation")
@RequiredArgsConstructor
@Tag(name = "Admin trade reconciliation")
public class AdminTradeReconciliationController {

    private final AdminTradeReconciliationDiagnosticsService diagnosticsService;

    @GetMapping("/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> diagnostics() {
        return ApiResponse.ok(diagnosticsService.fullDiagnostics(), CorrelationIdHolder.get());
    }

    @GetMapping("/pairs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> pairs(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(diagnosticsService.tradePairs(Math.min(limit, 200)), CorrelationIdHolder.get());
    }
}
