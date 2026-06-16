package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.service.StrategyPerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@RestController
@RequestMapping("/api/strategy/performance")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasRole('TRADER')")
@Tag(name = "Strategy Performance")
public class StrategyPerformanceController {

    private final StrategyPerformanceService strategyPerformanceService;

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get strategy performance metrics for a user")
    public ApiResponse<StrategyPerformanceService.StrategyPerformanceReport> getPerformance(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "SIMULATED") String executionMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        Instant start = startDate != null ?
                startDate.atStartOfDay(ZoneId.systemDefault()).toInstant() :
                LocalDate.now().minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant();

        Instant end = endDate != null ?
                endDate.plus(1, java.time.temporal.ChronoUnit.DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant() :
                Instant.now();

        StrategyPerformanceService.StrategyPerformanceReport report =
                strategyPerformanceService.getPerformance(userId, executionMode, start, end);

        return ApiResponse.ok(report, CorrelationIdHolder.get());
    }

    @GetMapping("/today")
    @Operation(summary = "Get today's strategy performance metrics")
    public ApiResponse<StrategyPerformanceService.StrategyPerformanceReport> getTodayPerformance(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "SIMULATED") String executionMode
    ) {
        LocalDate today = LocalDate.now();
        Instant start = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant end = today.plus(1, java.time.temporal.ChronoUnit.DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant();

        StrategyPerformanceService.StrategyPerformanceReport report =
                strategyPerformanceService.getPerformance(userId, executionMode, start, end);

        return ApiResponse.ok(report, CorrelationIdHolder.get());
    }
}
