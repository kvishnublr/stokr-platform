package com.stokr.strategy.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.telemetry.CorrelationIdHolder;
import com.stokr.strategy.dto.TraderExecutionConfigDto;
import com.stokr.strategy.dto.TraderExecutionConfigPatchRequest;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trader self-service endpoint for execution config.
 * All reads/writes are scoped to the authenticated trader's userId — a trader
 * can only view and edit their own configs.
 */
@RestController
@RequestMapping("/api/trader/execution-configs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TRADER','USER')")
public class TraderExecutionConfigController {

    private final StrategyExecutionConfigService configService;

    /** Returns all trader-specific config overrides for the authenticated user. */
    @GetMapping
    public ApiResponse<List<TraderExecutionConfigDto>> list(
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(configService.listForUser(user.getId()), CorrelationIdHolder.get());
    }

    /**
     * Returns the effective config for a strategy:
     * trader's own override if it exists, otherwise the global admin config (isGlobalFallback=true).
     */
    @GetMapping("/{strategyKey}")
    public ApiResponse<TraderExecutionConfigDto> get(
            @PathVariable String strategyKey,
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(
                configService.getOrCreateForUserDto(user.getId(), strategyKey),
                CorrelationIdHolder.get());
    }

    /**
     * Updates trader-editable fields only.
     * Admin-controlled fields (liveEnabled, executionMode, allocatedCapital) are never modified.
     * Creates a personal override row on first call.
     */
    @PatchMapping("/{strategyKey}")
    public ApiResponse<TraderExecutionConfigDto> patch(
            @PathVariable String strategyKey,
            @Valid @RequestBody TraderExecutionConfigPatchRequest req,
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(
                configService.patchForUser(user.getId(), strategyKey, req),
                CorrelationIdHolder.get());
    }
}
