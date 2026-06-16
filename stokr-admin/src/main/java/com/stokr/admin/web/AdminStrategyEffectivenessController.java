package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.simulation.AnalyticsDataScope;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.analytics.StrategyEffectivenessEngine;
import com.stokr.strategy.analytics.AlphaValidationEngine.AlphaValidationReport;
import com.stokr.strategy.analytics.StrategyEffectivenessEngine.StrategyEffectivenessReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/admin/strategy-effectiveness")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Strategy effectiveness")
public class AdminStrategyEffectivenessController {

    private final StrategyEffectivenessEngine effectivenessEngine;

    @GetMapping
    @Operation(summary = "Production strategy effectiveness scorecard, leaderboard, and V8 comparison")
    public ApiResponse<StrategyEffectivenessReport> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Instant v8Cutoff,
            @RequestParam(required = false, defaultValue = "REAL") String dataScope
    ) {
        return ApiResponse.ok(
                effectivenessEngine.buildReport(from, to, v8Cutoff, AnalyticsDataScope.parse(dataScope)),
                CorrelationIdHolder.get());
    }

    @GetMapping("/alpha-validation")
    @Operation(summary = "V8 alpha validation sprint ??? attribution, protection removal, capital tiers (production data)")
    public ApiResponse<AlphaValidationReport> alphaValidation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Instant v8Cutoff
    ) {
        return ApiResponse.ok(effectivenessEngine.buildAlphaValidationReport(from, to, v8Cutoff), CorrelationIdHolder.get());
    }
}
