package com.stokr.strategy.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.StrategyRuntimeMetricsDto;
import com.stokr.strategy.service.StrategyRuntimeObservabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/strategies/runtime-metrics")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Strategy runtime observability")
public class StrategyRuntimeObservabilityController {

    private final StrategyRuntimeObservabilityService observabilityService;

    @GetMapping
    @Operation(summary = "Runtime metrics for all instances belonging to the signed-in user")
    public ApiResponse<List<StrategyRuntimeMetricsDto>> list(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(observabilityService.metricsForUser(user.getId()), CorrelationIdHolder.get());
    }
}
