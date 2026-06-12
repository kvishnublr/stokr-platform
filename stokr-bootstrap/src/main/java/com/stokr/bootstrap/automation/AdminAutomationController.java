package com.stokr.bootstrap.automation;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/operations/automation")
@RequiredArgsConstructor
public class AdminAutomationController {

    private final PlatformAutomationService automationService;

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(automationService.status(), CorrelationIdHolder.get());
    }

    @PostMapping("/pre-market")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> preMarket() {
        return ApiResponse.ok(automationService.runPreMarket(), CorrelationIdHolder.get());
    }

    @PostMapping("/pre-open")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> preOpen() {
        return ApiResponse.ok(automationService.runPreOpen(), CorrelationIdHolder.get());
    }

    @PostMapping("/in-session")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> inSession() {
        return ApiResponse.ok(automationService.runInSessionMaintenance(), CorrelationIdHolder.get());
    }

    @PostMapping("/health-report")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> healthReport() {
        return ApiResponse.ok(automationService.runHealthReport(), CorrelationIdHolder.get());
    }

    @PostMapping("/recovery-cycle")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> recoveryCycle() {
        return ApiResponse.ok(automationService.runRecoveryCycle(), CorrelationIdHolder.get());
    }

    @PostMapping("/refresh-tokens")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> refreshTokens() {
        return ApiResponse.ok(automationService.refreshTokens(), CorrelationIdHolder.get());
    }
}
