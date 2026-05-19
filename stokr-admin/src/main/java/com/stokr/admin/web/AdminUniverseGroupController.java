package com.stokr.admin.web;

import com.stokr.admin.dto.BulkSymbolImportRequest;
import com.stokr.admin.dto.UniverseGroupCreateRequest;
import com.stokr.admin.dto.UniverseGroupDto;
import com.stokr.admin.dto.UniverseSymbolDto;
import com.stokr.admin.service.AdminUniverseGroupService;
import com.stokr.strategy.repository.StrategyUniverseSymbolRepository;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;

@RestController
@RequestMapping("/api/admin/universe-groups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin universe groups")
public class AdminUniverseGroupController {

    private final AdminUniverseGroupService service;
    private final StrategyUniverseSymbolRepository symbolRepository;

    @GetMapping
    @Operation(summary = "List all universe groups (paginated)")
    public ApiResponse<PageResponse<UniverseGroupDto>> list(
            @RequestParam(required = false) String assetClass,
            @PageableDefault(size = 50, sort = "groupKey") Pageable pageable
    ) {
        var page = assetClass != null && !assetClass.isBlank()
                ? service.pageByAssetClass(assetClass.toUpperCase(), pageable)
                : service.page(pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a universe group by ID")
    public ApiResponse<UniverseGroupDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id), CorrelationIdHolder.get());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new universe group")
    public ApiResponse<UniverseGroupDto> create(@Valid @RequestBody UniverseGroupCreateRequest body) {
        return ApiResponse.ok(service.create(body), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}/enabled")
    @Operation(summary = "Enable or disable a universe group")
    public ApiResponse<UniverseGroupDto> toggleEnabled(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body
    ) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.ok(service.toggleEnabled(id, enabled), CorrelationIdHolder.get());
    }

    @GetMapping("/{id}/symbols")
    @Operation(summary = "List all symbols in a universe group")
    public ApiResponse<List<UniverseSymbolDto>> symbols(@PathVariable UUID id) {
        return ApiResponse.ok(service.listSymbols(id), CorrelationIdHolder.get());
    }

    @PostMapping("/{id}/symbols/bulk-import")
    @Operation(summary = "Bulk import symbols into a universe group")
    public ApiResponse<Map<String, Integer>> bulkImport(
            @PathVariable UUID id,
            @Valid @RequestBody BulkSymbolImportRequest body
    ) {
        int imported = service.bulkImport(id, body);
        return ApiResponse.ok(Map.of("imported", imported), CorrelationIdHolder.get());
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "Trigger auto-sync for an auto-managed universe group (e.g. NIFTY_50)")
    public ApiResponse<Map<String, Integer>> sync(@PathVariable UUID id) {
        int synced = service.syncGroup(id);
        return ApiResponse.ok(Map.of("synced", synced), CorrelationIdHolder.get());
    }

    @GetMapping("/symbols/search")
    @Operation(summary = "Search symbols across all universe groups (for strategy symbol picker)")
    public ApiResponse<List<String>> searchSymbols(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "30") int limit
    ) {
        List<String> results = q.isBlank()
                ? symbolRepository.searchSymbols("", PageRequest.of(0, limit))
                : symbolRepository.searchSymbols(q.trim(), PageRequest.of(0, limit));
        return ApiResponse.ok(results, CorrelationIdHolder.get());
    }
}
