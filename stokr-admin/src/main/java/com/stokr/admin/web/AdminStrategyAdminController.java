package com.stokr.admin.web;

import com.stokr.admin.dto.AdminStrategyDto;
import com.stokr.admin.dto.AdminStrategyPatchRequest;
import com.stokr.admin.dto.StrategyCreateRequest;
import com.stokr.admin.service.AdminStrategyCatalogService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/strategies")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin strategies")
public class AdminStrategyAdminController {

    private final AdminStrategyCatalogService adminStrategyCatalogService;

    @GetMapping
    @Operation(summary = "List strategy definitions (paginated)")
    public ApiResponse<PageResponse<AdminStrategyDto>> list(
            @PageableDefault(size = 50, sort = "strategyKey") Pageable pageable
    ) {
        return ApiResponse.ok(PageResponse.of(adminStrategyCatalogService.page(pageable)), CorrelationIdHolder.get());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new strategy catalog entry (admin-managed)")
    public ApiResponse<AdminStrategyDto> create(
            @Valid @RequestBody StrategyCreateRequest body,
            @AuthenticationPrincipal UserDetails principal
    ) {
        UUID adminId = resolveAdminId(principal);
        return ApiResponse.ok(adminStrategyCatalogService.create(body, adminId), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update catalog flags / metadata for a strategy")
    public ApiResponse<AdminStrategyDto> patch(
            @PathVariable("id") UUID id,
            @Valid @RequestBody AdminStrategyPatchRequest body
    ) {
        return ApiResponse.ok(adminStrategyCatalogService.patch(id, body), CorrelationIdHolder.get());
    }

    @PostMapping("/{id}/generate-template")
    @Operation(summary = "Generate the Java strategy template file for this catalog entry")
    public ApiResponse<AdminStrategyDto> generateTemplate(@PathVariable("id") UUID id) {
        return ApiResponse.ok(adminStrategyCatalogService.generateTemplate(id), CorrelationIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Soft-delete a strategy catalog entry")
    public ApiResponse<Void> delete(@PathVariable("id") UUID id) {
        adminStrategyCatalogService.delete(id);
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    private UUID resolveAdminId(UserDetails principal) {
        if (principal == null) return null;
        try {
            return UUID.fromString(principal.getUsername());
        } catch (Exception e) {
            return null;
        }
    }
}
