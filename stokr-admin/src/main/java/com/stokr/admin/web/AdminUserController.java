package com.stokr.admin.web;

import com.stokr.admin.dto.AdminPasswordResetResponse;
import com.stokr.admin.dto.AdminUserDetailDto;
import com.stokr.admin.dto.AdminUserSummaryDto;
import com.stokr.admin.dto.LiveTradingApprovalRequest;
import com.stokr.admin.dto.UserStatusPatchRequest;
import com.stokr.admin.service.AdminUserService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    @Operation(summary = "Search and page registered users")
    public ApiResponse<PageResponse<AdminUserSummaryDto>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "registeredFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant registeredFrom,
            @RequestParam(value = "registeredTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant registeredTo
    ) {
        var page = adminUserService.search(pageable, search, enabled, role, registeredFrom, registeredTo);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/{id}")
    @Operation(summary = "User detail")
    public ApiResponse<AdminUserDetailDto> get(@PathVariable("id") UUID id) {
        return ApiResponse.ok(adminUserService.get(id), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Enable or disable user")
    public ApiResponse<AdminUserDetailDto> patchStatus(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UserStatusPatchRequest body,
            @AuthenticationPrincipal StokrUserDetails actor
    ) {
        return ApiResponse.ok(adminUserService.patchStatus(id, body, actor.getId()), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}/reset-password")
    @Operation(summary = "Generate a temporary password for the user")
    public ApiResponse<AdminPasswordResetResponse> resetPassword(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal StokrUserDetails actor
    ) {
        return ApiResponse.ok(adminUserService.resetPassword(id, actor.getId()), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}/live-trading")
    @Operation(summary = "Approve or revoke routing live broker orders for this trader")
    public ApiResponse<AdminUserDetailDto> patchLiveTrading(
            @PathVariable("id") UUID id,
            @Valid @RequestBody LiveTradingApprovalRequest body,
            @AuthenticationPrincipal StokrUserDetails actor
    ) {
        return ApiResponse.ok(
                adminUserService.patchLiveTradingApproval(id, body.liveTradingApproved(), actor.getId()),
                CorrelationIdHolder.get()
        );
    }
}
