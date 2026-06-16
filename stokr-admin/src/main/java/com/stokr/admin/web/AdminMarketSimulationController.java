package com.stokr.admin.web;

import com.stokr.admin.simulation.MarketSimulationHarnessService;
import com.stokr.admin.simulation.MarketSimulationHarnessService.SimulationHarnessReport;
import com.stokr.admin.simulation.MarketSimulationHarnessService.SimulationHarnessRequest;
import com.stokr.admin.simulation.SimulationCleanupService;
import com.stokr.admin.simulation.SimulationCleanupService.SimulationCleanupRequest;
import com.stokr.admin.simulation.SimulationDashboardService;
import com.stokr.admin.simulation.SimulationIsolationValidator;
import com.stokr.admin.simulation.SimulationValidationPackService;
import com.stokr.admin.simulation.SimulationValidationPackService.ValidationPackReport;
import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.common.simulation.SimulatedBrokerOutcome;
import com.stokr.common.simulation.SimulationModeService;
import com.stokr.common.simulation.SimulationRuntimeControlService;
import com.stokr.common.simulation.SimulationRuntimeControlService.SimulationRuntimeStatus;
import com.stokr.common.simulation.SimulationScenario;
import com.stokr.strategy.analytics.StrategyEffectivenessEngine;
import com.stokr.strategy.analytics.StrategyEffectivenessEngine.StrategyEffectivenessReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/simulation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Market simulation harness")
public class AdminMarketSimulationController {

    private final SimulationRuntimeControlService runtimeControl;
    private final SimulationModeService simulationMode;
    private final MarketSimulationHarnessService harnessService;
    private final SimulationDashboardService dashboardService;
    private final SimulationCleanupService cleanupService;
    private final SimulationValidationPackService validationPackService;
    private final StrategyEffectivenessEngine effectivenessEngine;
    private final SimulationIsolationValidator isolationValidator;

    @GetMapping("/runtime/status")
    @Operation(summary = "Simulation runtime toggle status (explicit admin enable required)")
    public ApiResponse<SimulationRuntimeStatus> runtimeStatus() {
        return ApiResponse.ok(runtimeControl.status(), CorrelationIdHolder.get());
    }

    @PostMapping("/runtime/enable")
    @Operation(summary = "Enable simulation at runtime (ADMIN only)")
    public ApiResponse<SimulationRuntimeStatus> enableRuntime(
            @AuthenticationPrincipal StokrUserDetails principal
    ) {
        UUID adminId = principal != null ? principal.getId() : null;
        runtimeControl.enable(adminId);
        return ApiResponse.ok(runtimeControl.status(), CorrelationIdHolder.get());
    }

    @PostMapping("/runtime/disable")
    @Operation(summary = "Disable simulation at runtime")
    public ApiResponse<SimulationRuntimeStatus> disableRuntime() {
        runtimeControl.disable();
        return ApiResponse.ok(runtimeControl.status(), CorrelationIdHolder.get());
    }

    @GetMapping("/scenarios")
    public ApiResponse<List<String>> scenarios() {
        return ApiResponse.ok(
                Arrays.stream(SimulationScenario.values()).map(Enum::name).collect(Collectors.toList()),
                CorrelationIdHolder.get());
    }

    @GetMapping("/broker-outcomes")
    public ApiResponse<List<String>> brokerOutcomes() {
        return ApiResponse.ok(
                Arrays.stream(SimulatedBrokerOutcome.values()).map(Enum::name).collect(Collectors.toList()),
                CorrelationIdHolder.get());
    }

    @PostMapping("/run")
    @Operation(summary = "Run one E2E scenario (requires runtime enable)")
    public ApiResponse<SimulationHarnessReport> run(@RequestBody RunScenarioRequest body) {
        requireRuntimeActive();
        return ApiResponse.ok(harnessService.runScenario(toHarnessRequest(body)), CorrelationIdHolder.get());
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Simulation dashboard ??? runs, signals, orders, outcomes")
    public ApiResponse<SimulationDashboardService.SimulationDashboardSnapshot> dashboard(
            @RequestParam(required = false) UUID runId
    ) {
        return ApiResponse.ok(dashboardService.dashboard(runId), CorrelationIdHolder.get());
    }

    @GetMapping("/analytics")
    @Operation(summary = "Effectiveness view by data scope: REAL | SIMULATION | MIXED")
    public ApiResponse<StrategyEffectivenessReport> analytics(
            @RequestParam(defaultValue = "SIMULATION") String dataScope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ApiResponse.ok(
                effectivenessEngine.buildReport(from, to, null, AnalyticsDataScope.parse(dataScope)),
                CorrelationIdHolder.get());
    }

    @GetMapping("/isolation-check")
    @Operation(summary = "Verify REAL OMS/capital metrics exclude simulation data")
    public ApiResponse<SimulationIsolationValidator.IsolationCheckResult> isolationCheck(
            @RequestParam(required = false) UUID runId
    ) {
        return ApiResponse.ok(isolationValidator.check(runId), CorrelationIdHolder.get());
    }

    @PostMapping("/validate-release")
    @Operation(summary = "E2E validation pack for release gate")
    public ApiResponse<ValidationPackReport> validateRelease() {
        requireRuntimeActive();
        return ApiResponse.ok(validationPackService.runPack(), CorrelationIdHolder.get());
    }

    @DeleteMapping("/cleanup")
    @Operation(summary = "Soft-delete simulation artifacts by run id, scenario, or date range")
    public ApiResponse<java.util.Map<String, Object>> cleanup(@RequestBody CleanupRequest body) {
        return ApiResponse.ok(
                cleanupService.cleanup(new SimulationCleanupRequest(
                        body.runId(),
                        body.scenario(),
                        body.from(),
                        body.toExclusive()
                )),
                CorrelationIdHolder.get());
    }

    private void requireRuntimeActive() {
        if (!simulationMode.isActive()) {
            throw new IllegalStateException(
                    "Simulation runtime is disabled. Enable via POST /api/admin/simulation/runtime/enable");
        }
    }

    private static SimulationHarnessRequest toHarnessRequest(RunScenarioRequest body) {
        return new SimulationHarnessRequest(
                body.scenario() != null ? body.scenario() : SimulationScenario.CUSTOM,
                body.strategyKey(),
                body.symbol(),
                body.basePrice(),
                body.sessionBars() != null ? body.sessionBars() : 120,
                body.executionMode(),
                body.brokerOutcome(),
                Boolean.TRUE.equals(body.useCatalogScan()),
                body.runProtectionMonitor() != null
                        ? body.runProtectionMonitor()
                        : body.scenario() == SimulationScenario.PROTECTION_EXIT,
                body.pushLiveTicks() != null ? body.pushLiveTicks() : 0,
                body.tickPriceOverride()
        );
    }

    public record RunScenarioRequest(
            SimulationScenario scenario,
            String strategyKey,
            String symbol,
            BigDecimal basePrice,
            Integer sessionBars,
            String executionMode,
            SimulatedBrokerOutcome brokerOutcome,
            Boolean useCatalogScan,
            Boolean runProtectionMonitor,
            Integer pushLiveTicks,
            BigDecimal tickPriceOverride
    ) {
    }

    public record CleanupRequest(
            UUID runId,
            String scenario,
            Instant from,
            Instant toExclusive
    ) {
    }
}
