package com.stokr.admin.web;

import com.stokr.admin.service.AdminOmsDiagnosticsService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.safety.TradingKillSwitchService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/oms")
@RequiredArgsConstructor
@Tag(name = "Admin OMS safety")
public class AdminOmsSafetyController {

    private final TradingKillSwitchService killSwitchService;
    private final AdminOmsDiagnosticsService diagnosticsService;

    @GetMapping("/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> diagnostics(
            @RequestParam(value = "userId", required = false) UUID userId) {
        return ApiResponse.ok(diagnosticsService.diagnostics(userId), CorrelationIdHolder.get());
    }

    @GetMapping("/kill-switch/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> killSwitchStatus() {
        return ApiResponse.ok(killSwitchService.statusMap(0), CorrelationIdHolder.get());
    }

    @PostMapping("/kill-switch/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> activateKillSwitch(
            @RequestParam(value = "reason", defaultValue = "Admin manual activation") String reason,
            @RequestParam(value = "flatten", defaultValue = "false") boolean flatten,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "admin";
        return ApiResponse.ok(
                killSwitchService.activate(TradingKillSwitchService.TriggerSource.ADMIN_API, reason, flatten, actor),
                CorrelationIdHolder.get());
    }

    @PostMapping("/kill-switch/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> deactivateKillSwitch(
            @RequestParam(value = "reason", defaultValue = "Admin manual deactivation") String reason,
            Authentication auth) {
        String actor = auth != null ? auth.getName() : "admin";
        return ApiResponse.ok(
                killSwitchService.deactivate(TradingKillSwitchService.TriggerSource.ADMIN_API, reason, actor),
                CorrelationIdHolder.get());
    }
}
