package com.stokr.admin.web;

import com.stokr.admin.domain.AuditLog;
import com.stokr.admin.repository.AuditLogRepository;
import com.stokr.admin.service.AdminOperationalSnapshotService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.pipeline.PipelineQueues;
import com.stokr.execution.safety.TradingKillSwitchService;
import com.stokr.risk.service.KillSwitchService;
import com.stokr.risk.service.LiveTradingArmingService;
import com.stokr.risk.service.StrategyToggleService;
import com.stokr.strategy.repository.StrategyDefinitionRepository;
import com.stokr.strategy.service.StrategyEmergencyStopService;
import com.stokr.user.repository.PlatformBrokerFeedSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final KillSwitchService killSwitchService;
    private final TradingKillSwitchService tradingKillSwitchService;
    private final LiveTradingArmingService liveTradingArmingService;
    private final StrategyToggleService strategyToggleService;
    private final StrategyEmergencyStopService strategyEmergencyStopService;
    private final AuditLogRepository auditLogRepository;
    private final AdminOperationalSnapshotService adminOperationalSnapshotService;
    private final StrategyDefinitionRepository strategyDefinitionRepository;
    private final PlatformBrokerFeedSessionRepository feedSessionRepository;

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("killSwitch", tradingKillSwitchService.isActive());
        body.put("killSwitchLegacy", killSwitchService.isEnabled());
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
        if (enabled) {
            return ApiResponse.ok(tradingKillSwitchService.activate(
                    TradingKillSwitchService.TriggerSource.ADMIN_API,
                    "Legacy /kill-switch endpoint",
                    false,
                    "admin"), CorrelationIdHolder.get());
        }
        return ApiResponse.ok(tradingKillSwitchService.deactivate(
                TradingKillSwitchService.TriggerSource.ADMIN_API,
                "Legacy /kill-switch endpoint",
                "admin"), CorrelationIdHolder.get());
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

    @GetMapping("/settings/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> settingsSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        m.put("killSwitch", killSwitchService.isEnabled() ? "ENGAGED" : "OFF");
        m.put("liveTradingArmed", liveTradingArmingService.isArmed() ? "ARMED" : "DISARMED");
        m.put("uptimeSeconds", uptimeSec);
        m.put("uptimeHuman", String.format("%dh %dm", uptimeSec / 3600, (uptimeSec % 3600) / 60));
        long totalStrategies = strategyDefinitionRepository.countByDeletedFalse();
        m.put("strategiesTotal", totalStrategies);
        feedSessionRepository.findByVendorCodeIgnoreCaseAndDeletedFalse("ZERODHA").ifPresent(s -> {
            m.put("marketFeedState", s.getWebsocketState() != null ? s.getWebsocketState() : "UNKNOWN");
            m.put("marketFeedSubscriptions", s.getSubscriptionCount());
            m.put("marketFeedTicksPerSec", s.getTicksPerSec() != null ? String.format("%.1f", s.getTicksPerSec()) : "-");
            m.put("marketFeedLastPacket", s.getLastPacketAt() != null ? s.getLastPacketAt().toString() : "never");
        });
        m.put("queues", Map.of(
                "strategySignal", PipelineQueues.STRATEGY_SIGNAL,
                "omsOrder", PipelineQueues.OMS_ORDER,
                "execution", PipelineQueues.EXECUTION
        ));
        return ApiResponse.ok(m, CorrelationIdHolder.get());
    }

    @GetMapping("/security/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> securitySummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        long recentAudit = auditLogRepository.findAllByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(0, 1)).getTotalElements();
        m.put("auditLogEntries", recentAudit);
        m.put("killSwitchActive", killSwitchService.isEnabled());
        m.put("liveTradingArmed", liveTradingArmingService.isArmed());
        m.put("asOf", Instant.now().toString());
        return ApiResponse.ok(m, CorrelationIdHolder.get());
    }

    @GetMapping("/reports/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> reportsSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", "Detailed reports coming soon");
        m.put("asOf", Instant.now().toString());
        return ApiResponse.ok(m, CorrelationIdHolder.get());
    }
}
