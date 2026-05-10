package com.stokr.admin.web;

import com.stokr.admin.dto.AdminStrategyDto;
import com.stokr.admin.dto.AdminStrategyPatchRequest;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    @Operation(summary = "List strategy definitions")
    public ApiResponse<PageResponse<AdminStrategyDto>> list(
            @PageableDefault(size = 50, sort = "strategyKey") Pageable pageable
    ) {
        var page = adminStrategyCatalogService.page(pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update catalog flags for a strategy")
    public ApiResponse<AdminStrategyDto> patch(@PathVariable("id") UUID id, @Valid @RequestBody AdminStrategyPatchRequest body) {
        return ApiResponse.ok(adminStrategyCatalogService.patch(id, body), CorrelationIdHolder.get());
    }
}
