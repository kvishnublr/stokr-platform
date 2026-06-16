package com.stokr.admin.web;

import com.stokr.admin.dto.RuntimeBindingCreateRequest;
import com.stokr.admin.dto.RuntimeBindingDto;
import com.stokr.admin.service.AdminRuntimeBindingService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/runtime-bindings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin runtime bindings")
public class AdminRuntimeBindingController {

    private final AdminRuntimeBindingService service;

    @GetMapping
    @Operation(summary = "List all runtime bindings (paginated)")
    public ApiResponse<PageResponse<RuntimeBindingDto>> list(
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ApiResponse.ok(PageResponse.of(service.page(pageable)), CorrelationIdHolder.get());
    }

    @GetMapping("/by-strategy/{strategyCatalogId}")
    @Operation(summary = "List all bindings for a specific strategy")
    public ApiResponse<List<RuntimeBindingDto>> byStrategy(@PathVariable UUID strategyCatalogId) {
        return ApiResponse.ok(service.listForStrategy(strategyCatalogId), CorrelationIdHolder.get());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new strategy-to-universe runtime binding")
    public ApiResponse<RuntimeBindingDto> create(@Valid @RequestBody RuntimeBindingCreateRequest body) {
        return ApiResponse.ok(service.create(body), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update binding parameters (scan interval, capital, risk, positions)")
    public ApiResponse<RuntimeBindingDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody RuntimeBindingCreateRequest body
    ) {
        return ApiResponse.ok(service.update(id, body), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}/runtime-enabled")
    @Operation(summary = "Enable or disable runtime scanning for a binding")
    public ApiResponse<RuntimeBindingDto> toggleRuntime(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body
    ) {
        boolean enabled = Boolean.TRUE.equals(body.get("runtimeEnabled"));
        return ApiResponse.ok(service.toggleRuntime(id, enabled), CorrelationIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a runtime binding")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(CorrelationIdHolder.get());
    }
}
