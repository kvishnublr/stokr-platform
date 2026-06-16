package com.stokr.strategy.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.UpdateStrategyInstanceRequest;
import com.stokr.strategy.dto.UserStrategyInstanceDto;
import com.stokr.strategy.service.StrategyInstanceLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/strategies/instances")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Strategy instances")
public class StrategyInstanceController {

    private final StrategyInstanceLifecycleService lifecycleService;

    @GetMapping
    @Operation(summary = "List current user's strategy instances")
    public ApiResponse<PageResponse<UserStrategyInstanceDto>> list(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        var page = lifecycleService.pageForUser(user.getId(), pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Mark instance as running")
    public ApiResponse<UserStrategyInstanceDto> start(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(lifecycleService.start(user.getId(), id), CorrelationIdHolder.get());
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Stop instance runtime")
    public ApiResponse<UserStrategyInstanceDto> stop(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(lifecycleService.stop(user.getId(), id), CorrelationIdHolder.get());
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause instance runtime")
    public ApiResponse<UserStrategyInstanceDto> pause(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(lifecycleService.pause(user.getId(), id), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update symbol, execution mode, allocation, risk")
    public ApiResponse<UserStrategyInstanceDto> patch(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateStrategyInstanceRequest body,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(lifecycleService.patch(user.getId(), id, body), CorrelationIdHolder.get());
    }
}
