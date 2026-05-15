package com.stokr.admin.web;

import com.stokr.admin.domain.AuditLog;
import com.stokr.admin.repository.AuditLogRepository;
import com.stokr.admin.service.AdminOperationalSnapshotService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.risk.service.KillSwitchService;
import com.stokr.risk.service.LiveTradingArmingService;
import com.stokr.risk.service.StrategyToggleService;
import com.stokr.strategy.service.StrategyEmergencyStopService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final KillSwitchService killSwitchService;
    private final LiveTradingArmingService liveTradingArmingService;
    private final StrategyToggleService strategyToggleService;
    private final StrategyEmergencyStopService strategyEmergencyStopService;
    private final AuditLogRepository auditLogRepository;
    private final AdminOperationalSnapshotService adminOperationalSnapshotService;

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("killSwitch", killSwitchService.isEnabled());
        body.put("liveTradingArmed", liveTradingArmingService.isArmed());
        body.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        body.put("queues", Map.of(
                "strategySignal", PipelineQueues.STRATEGY_SIGNAL,
                "omsOrder", PipelineQueues.OMS_ORDER,
                "execution", PipelineQueues.EXECUTION
        ));
        return ApiResponse.ok(body, CorrelationIdHolder.get());
    }

    @PostMapping("/strategy/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> toggleStrategy(
            @RequestParam("strategyKey") String strategyKey,
            @RequestParam("enabled") boolean enabled
    ) {
        strategyToggleService.setEnabled(strategyKey, enabled);
        return ApiResponse.ok(Map.of("strategyKey", strategyKey, "enabled", enabled), CorrelationIdHolder.get());
    }

    @PostMapping("/kill-switch")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> killSwitch(@RequestParam("enabled") boolean enabled) {
        killSwitchService.setEnabled(enabled);
        return ApiResponse.ok(Map.of("enabled", enabled), CorrelationIdHolder.get());
    }

    @PostMapping("/live-trading/arm")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> armLiveTrading(@RequestParam("armed") boolean armed) {
        liveTradingArmingService.setArmed(armed);
        return ApiResponse.ok(Map.of("armed", armed), CorrelationIdHolder.get());
    }

    @PostMapping("/strategies/emergency-stop")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> emergencyStopStrategies() {
        int n = strategyEmergencyStopService.stopAllRunning();
        return ApiResponse.ok(Map.of("stoppedInstances", n), CorrelationIdHolder.get());
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<AuditLog>> audit(@RequestParam(value = "page", defaultValue = "0") int page,
                                             @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(
                auditLogRepository.findAllByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(page, size)).getContent(),
                CorrelationIdHolder.get()
        );
    }

    /**
     * Rule-derived incidents (same evaluator as operations snapshot). Lightweight clients can poll this without full snapshot.
     */
    @GetMapping("/alerts")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> alerts() {
        return ApiResponse.ok(adminOperationalSnapshotService.snapshot().incidents(), CorrelationIdHolder.get());
    }
}
