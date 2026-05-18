package com.stokr.admin.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.guard.ExecutionGuardMode;
import com.stokr.execution.guard.ExecutionGuardPolicyRegistryService;
import com.stokr.execution.guard.ExecutionGuardPolicyService;
import com.stokr.execution.guard.ExecutionGuardScopeType;
import com.stokr.execution.guard.ExecutionQualityScoringService;
import com.stokr.execution.guard.ExecutionTimelineService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/execution-guard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminExecutionGuardController {

    private final ExecutionGuardPolicyRegistryService policyRegistryService;
    private final ExecutionGuardPolicyService policyService;
    private final ExecutionTimelineService timelineService;
    private final ExecutionQualityScoringService qualityScoringService;

    public record PolicyPatchRequest(
            String scopeType,
            String scopeKey,
            String guardMode,
            Map<String, Object> patch
    ) {}

    @GetMapping("/policies")
    @Operation(summary = "List effective execution guard policy overrides")
    public ApiResponse<List<Map<String, Object>>> policies(@RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ApiResponse.ok(policyRegistryService.listOverrides(limit), CorrelationIdHolder.get());
    }

    @PostMapping("/policies")
    @Operation(summary = "Upsert execution guard policy override")
    public ApiResponse<Map<String, Object>> upsertPolicy(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestBody PolicyPatchRequest body
    ) {
        ExecutionGuardScopeType scopeType = ExecutionGuardScopeType.valueOf(body.scopeType().trim().toUpperCase());
        ExecutionGuardMode guardMode = body.guardMode() == null || body.guardMode().isBlank()
                ? null
                : ExecutionGuardMode.valueOf(body.guardMode().trim().toUpperCase());
        policyRegistryService.upsertOverride(user.getId(), scopeType, body.scopeKey(), guardMode, body.patch() == null ? Map.of() : body.patch());
        return ApiResponse.ok(Map.of("ok", true, "message", "Policy override updated"), CorrelationIdHolder.get());
    }

    @DeleteMapping("/policies/{id}")
    @Operation(summary = "Disable policy override by id")
    public ApiResponse<Map<String, Object>> disablePolicy(
            @AuthenticationPrincipal StokrUserDetails user,
            @PathVariable("id") UUID id
    ) {
        policyRegistryService.disableOverride(user.getId(), id);
        return ApiResponse.ok(Map.of("ok", true, "message", "Policy override disabled"), CorrelationIdHolder.get());
    }

    @PostMapping("/policies/reload")
    @Operation(summary = "Live reload policy cache")
    public ApiResponse<Map<String, Object>> reloadPolicy() {
        policyService.reloadPolicies();
        return ApiResponse.ok(Map.of("ok", true, "message", "Policy cache reloaded"), CorrelationIdHolder.get());
    }

    @GetMapping("/policies/audit")
    @Operation(summary = "Policy audit trail")
    public ApiResponse<List<Map<String, Object>>> policyAudit(@RequestParam(value = "limit", defaultValue = "200") int limit) {
        return ApiResponse.ok(policyRegistryService.listAudit(limit), CorrelationIdHolder.get());
    }

    @GetMapping("/timeline")
    @Operation(summary = "Recent execution timeline events for given user")
    public ApiResponse<List<Map<String, Object>>> timeline(
            @RequestParam("userId") UUID userId,
            @RequestParam(value = "limit", defaultValue = "300") int limit
    ) {
        return ApiResponse.ok(timelineService.recentForUser(userId, limit), CorrelationIdHolder.get());
    }

    @GetMapping("/quality-score")
    @Operation(summary = "Execution quality score for given user")
    public ApiResponse<Map<String, Object>> qualityScore(@RequestParam("userId") UUID userId) {
        return ApiResponse.ok(qualityScoringService.scoreForUser(userId), CorrelationIdHolder.get());
    }
}

