package com.stokr.admin.web;

import com.stokr.admin.dto.TestSignalLabDtos.TestSignalExecutionReport;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalLabRequest;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalPreflightReport;
import com.stokr.admin.dto.TestSignalLabDtos.TestSignalRunSummaryDto;
import com.stokr.admin.service.AdminTestSignalLabService;
import com.stokr.strategy.catalog.CommoditiesE2eTestTriggerService;
import com.stokr.admin.service.AdminTestSignalLabRemediationService;
import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/test-signal-lab")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Test Signal Lab")
public class AdminTestSignalLabController {

    private final AdminTestSignalLabService testSignalLabService;
    private final AdminTestSignalLabRemediationService remediationService;
    private final CommoditiesE2eTestTriggerService commoditiesE2eTestTriggerService;

    public record RemediationRequest(String actionCode, UUID traderUserId, String vendor) {}

    public record QueueScannerFireRequest(String symbol) {}

    @PostMapping("/run")
    @Operation(summary = "Run isolated end-to-end test signal through production execution pipeline")
    public ApiResponse<TestSignalExecutionReport> runTest(
            @AuthenticationPrincipal StokrUserDetails principal,
            @Valid @RequestBody TestSignalLabRequest request
    ) {
        UUID requestedBy = principal != null ? principal.getId() : request.traderUserId();
        return ApiResponse.ok(testSignalLabService.run(requestedBy, request), CorrelationIdHolder.get());
    }

    @PostMapping("/preflight")
    @Operation(summary = "Run preflight checks and return submit eligibility for test execution")
    public ApiResponse<TestSignalPreflightReport> preflight(
            @RequestBody TestSignalLabRequest request
    ) {
        return ApiResponse.ok(testSignalLabService.preflight(request), CorrelationIdHolder.get());
    }

    @GetMapping("/runs")
    @Operation(summary = "List historical test signal runs")
    public ApiResponse<PageResponse<TestSignalRunSummaryDto>> runs(
            @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.ok(PageResponse.of(testSignalLabService.list(pageable)), CorrelationIdHolder.get());
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Detailed execution verification report for a test run")
    public ApiResponse<TestSignalExecutionReport> report(@PathVariable UUID id) {
        return ApiResponse.ok(testSignalLabService.report(id), CorrelationIdHolder.get());
    }

    @GetMapping("/options")
    @Operation(summary = "Load options needed by test lab form")
    public ApiResponse<Map<String, Object>> options() {
        return ApiResponse.ok(testSignalLabService.options(), CorrelationIdHolder.get());
    }

    @GetMapping("/telemetry")
    @Operation(summary = "Aggregate test-lab telemetry and SLA metrics")
    public ApiResponse<Map<String, Object>> telemetry() {
        return ApiResponse.ok(testSignalLabService.telemetry(), CorrelationIdHolder.get());
    }

    @PostMapping("/remediate")
    @Operation(summary = "Execute remediation action for test diagnostics")
    public ApiResponse<Map<String, Object>> remediate(@RequestBody RemediationRequest request) {
        return ApiResponse.ok(
                remediationService.remediate(request.actionCode(), request.traderUserId(), request.vendor()),
                CorrelationIdHolder.get()
        );
    }

    @PostMapping("/queue-scanner-fire")
    @Operation(summary = "Queue a one-shot COMMODITIES_E2E_TEST scanner signal for the symbol")
    public ApiResponse<Map<String, Object>> queueScannerFire(@RequestBody QueueScannerFireRequest request) {
        String symbol = request.symbol() == null || request.symbol().isBlank() ? "CRUDEOIL" : request.symbol().trim();
        commoditiesE2eTestTriggerService.queueFire(symbol);
        return ApiResponse.ok(Map.of(
                "queued", true,
                "symbol", symbol,
                "strategyKey", "COMMODITIES_E2E_TEST",
                "message", "Next scanner cycle will emit a probe signal if data is fresh"
        ), CorrelationIdHolder.get());
    }
}
