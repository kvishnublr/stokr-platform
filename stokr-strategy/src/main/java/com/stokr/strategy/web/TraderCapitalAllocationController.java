package com.stokr.strategy.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.TraderCapitalAllocationView;
import com.stokr.strategy.service.StrategyExecutionConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trader self-service capital allocation. Maps the four asset-class buckets
 * (CASH | F&O | CURRENCY | MCX) to their member strategies' execution configs,
 * scoped to the authenticated trader's userId. The fan-out to per-strategy rows
 * happens server-side so the client persists in a single call.
 */
@RestController
@RequestMapping("/api/trader/capital-allocation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TRADER','USER')")
public class TraderCapitalAllocationController {

    private final StrategyExecutionConfigService configService;

    /** Effective capital allocation across asset classes for the authenticated trader. */
    @GetMapping
    public ApiResponse<TraderCapitalAllocationView> get(
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(configService.getCapitalAllocation(user.getId()), CorrelationIdHolder.get());
    }

    /** Persists the capital allocation, fanning each bucket out to its member strategies. */
    @PutMapping
    public ApiResponse<TraderCapitalAllocationView> save(
            @RequestBody TraderCapitalAllocationView req,
            @AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(configService.saveCapitalAllocation(user.getId(), req), CorrelationIdHolder.get());
    }
}
