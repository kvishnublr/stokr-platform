package com.stokr.strategy.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.StrategyCatalogItemDto;
import com.stokr.strategy.dto.StrategyCatalogSignalStatsDto;
import com.stokr.strategy.dto.metadata.StrategyMetadataResponseDto;
import com.stokr.strategy.service.StrategyCatalogQueryService;
import com.stokr.strategy.service.StrategyCatalogSignalStatsService;
import com.stokr.strategy.service.StrategyMetadataQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stokr.auth.security.StokrUserDetails;

import java.util.List;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
@Tag(name = "Strategies (catalog)")
public class StrategyCatalogController {

    private final StrategyCatalogQueryService catalogQueryService;
    private final StrategyCatalogSignalStatsService catalogSignalStatsService;
    private final StrategyMetadataQueryService strategyMetadataQueryService;

    @GetMapping("/metadata/{strategyKey}")
    @Operation(summary = "Parameter schema + execution capabilities for a strategy (metadata-driven UI)")
    public ApiResponse<StrategyMetadataResponseDto> strategyMetadata(@PathVariable("strategyKey") String strategyKey) {
        return ApiResponse.ok(strategyMetadataQueryService.byStrategyKey(strategyKey), CorrelationIdHolder.get());
    }

    @GetMapping("/catalog")
    @Operation(summary = "List strategies available to traders (enabled + visible)")
    public ApiResponse<PageResponse<StrategyCatalogItemDto>> catalog(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "riskLevel", required = false) String riskLevel
    ) {
        var page = catalogQueryService.catalogForCatalog(
                pageable,
                user != null ? user.getId() : null,
                category,
                riskLevel
        );
        return ApiResponse.ok(PageResponse.of(page), CorrelationIdHolder.get());
    }

    @GetMapping("/catalog/signal-stats")
    @Operation(summary = "Live/paper signal counts for today grouped by strategy key (platform scanner inclusive)")
    public ApiResponse<List<StrategyCatalogSignalStatsDto>> catalogSignalStats() {
        return ApiResponse.ok(catalogSignalStatsService.signalsTodayByStrategyKey(), CorrelationIdHolder.get());
    }
}
