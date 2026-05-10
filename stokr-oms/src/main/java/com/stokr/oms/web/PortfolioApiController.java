package com.stokr.oms.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.oms.dto.PortfolioDashboardDto;
import com.stokr.oms.dto.PortfolioEquityPointDto;
import com.stokr.oms.dto.PortfolioExposureDto;
import com.stokr.oms.dto.PortfolioOverviewDto;
import com.stokr.oms.service.PortfolioQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Portfolio")
public class PortfolioApiController {

    private final PortfolioQueryService portfolioQueryService;

    @GetMapping("/overview")
    @Operation(summary = "Current portfolio snapshot")
    public ApiResponse<PortfolioOverviewDto> overview(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(portfolioQueryService.overview(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/equity-curve")
    @Operation(summary = "Historical equity / PnL snapshots")
    public ApiResponse<List<PortfolioEquityPointDto>> equityCurve(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestParam(value = "points", defaultValue = "180") int points
    ) {
        return ApiResponse.ok(portfolioQueryService.equityCurve(user.getId(), Math.min(points, 500)), CorrelationIdHolder.get());
    }

    @GetMapping("/exposure")
    @Operation(summary = "Symbol and broker-traded notional exposure")
    public ApiResponse<PortfolioExposureDto> exposure(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(portfolioQueryService.exposure(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Dashboard aggregate (overview + curve + exposure + drawdown hints)")
    public ApiResponse<PortfolioDashboardDto> dashboard(
            @AuthenticationPrincipal StokrUserDetails user,
            @RequestParam(value = "equityPoints", defaultValue = "180") int equityPoints
    ) {
        return ApiResponse.ok(portfolioQueryService.dashboard(user.getId(), Math.min(equityPoints, 500)), CorrelationIdHolder.get());
    }
}
