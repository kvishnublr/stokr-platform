package com.stokr.admin.web;

import com.stokr.admin.signal.AdminSignalDetailDto;
import com.stokr.admin.signal.AdminSignalDto;
import com.stokr.admin.signal.AdminSignalParams;
import com.stokr.admin.signal.AdminSignalQueryService;
import com.stokr.admin.signal.AdminSignalStatsDto;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.service.SignalOutcomeTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/signals")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Signal monitor")
public class AdminSignalController {

    private final AdminSignalQueryService queryService;
    private final SignalOutcomeTrackerService outcomeTrackerService;

    @GetMapping("/stats")
    @Operation(summary = "Aggregate signal stats for today or custom window")
    public ApiResponse<AdminSignalStatsDto> stats(
            @RequestParam(required = false) Instant since
    ) {
        Instant from = since != null ? since
                : Instant.now().atZone(java.time.ZoneId.of("Asia/Kolkata")).truncatedTo(ChronoUnit.DAYS).toInstant();
        return ApiResponse.ok(queryService.stats(from), CorrelationIdHolder.get());
    }

    @GetMapping
    @Operation(summary = "Paginated signal list with optional filters")
    public ApiResponse<PageResponse<AdminSignalDto>> signals(
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String strategyName,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String signalType,
            @RequestParam(required = false) String pipeline,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String outcomeStatus
    ) {
        var page = queryService.pageSignals(
                new AdminSignalParams(strategyName, symbol, signalType, pipeline, userId, from, to, outcomeStatus),
                pageable);
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Signal detail with linked orders and execution timeline")
    public ApiResponse<AdminSignalDetailDto> detail(@PathVariable UUID id) {
        return ApiResponse.ok(queryService.detail(id), CorrelationIdHolder.get());
    }

    @PostMapping("/track-outcomes")
    @Operation(summary = "Immediately run outcome tracking for all pending signals (admin trigger)")
    public ApiResponse<Map<String, String>> trackOutcomes() {
        outcomeTrackerService.trackOutcomes();
        return ApiResponse.ok(Map.of("status", "outcome tracking completed"), CorrelationIdHolder.get());
    }
}
