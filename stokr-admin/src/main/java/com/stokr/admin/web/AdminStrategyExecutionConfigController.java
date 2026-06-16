package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.StrategyExecutionConfigDto;
import com.stokr.strategy.dto.StrategyExecutionConfigRequest;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/strategy-execution-configs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Strategy Execution Config")
public class AdminStrategyExecutionConfigController {

    private final StrategyExecutionConfigService service;

    @GetMapping
    @Operation(summary = "List all strategy execution configs")
    public ApiResponse<List<StrategyExecutionConfigDto>> list() {
        return ApiResponse.ok(service.listAll(), CorrelationIdHolder.get());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single execution config by id")
    public ApiResponse<StrategyExecutionConfigDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.getById(id), CorrelationIdHolder.get());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new strategy execution config")
    public ApiResponse<StrategyExecutionConfigDto> create(@Valid @RequestBody StrategyExecutionConfigRequest body) {
        return ApiResponse.ok(service.create(body), CorrelationIdHolder.get());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Full update of a strategy execution config")
    public ApiResponse<StrategyExecutionConfigDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody StrategyExecutionConfigRequest body) {
        return ApiResponse.ok(service.update(id, body), CorrelationIdHolder.get());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partial update of a strategy execution config")
    public ApiResponse<StrategyExecutionConfigDto> patch(
            @PathVariable UUID id,
            @Valid @RequestBody StrategyExecutionConfigRequest body) {
        return ApiResponse.ok(service.patch(id, body), CorrelationIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Soft-delete a strategy execution config")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(CorrelationIdHolder.get());
    }
}
