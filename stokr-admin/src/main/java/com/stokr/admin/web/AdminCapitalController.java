package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.capital.GlobalCapitalSummary;
import com.stokr.strategy.capital.StrategyCapitalManager;
import com.stokr.strategy.capital.StrategyCapitalSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/capital")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Capital")
public class AdminCapitalController {

    private final StrategyCapitalManager strategyCapitalManager;

    @GetMapping
    @Operation(summary = "Get global capital summary across all strategies")
    public ApiResponse<GlobalCapitalSummary> global() {
        return ApiResponse.ok(strategyCapitalManager.globalSummary(), CorrelationIdHolder.get());
    }

    @GetMapping("/{strategyKey}")
    @Operation(summary = "Get capital summary for a single strategy")
    public ApiResponse<StrategyCapitalSummary> byStrategy(@PathVariable String strategyKey) {
        return ApiResponse.ok(strategyCapitalManager.summarizeStrategy(strategyKey), CorrelationIdHolder.get());
    }
}
