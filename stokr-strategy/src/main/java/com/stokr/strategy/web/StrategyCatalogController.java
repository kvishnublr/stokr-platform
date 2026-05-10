package com.stokr.strategy.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.api.PageResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.StrategyCatalogItemDto;
import com.stokr.strategy.service.StrategyCatalogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stokr.auth.security.StokrUserDetails;

@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
@Tag(name = "Strategies (catalog)")
public class StrategyCatalogController {

    private final StrategyCatalogQueryService catalogQueryService;

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
}
