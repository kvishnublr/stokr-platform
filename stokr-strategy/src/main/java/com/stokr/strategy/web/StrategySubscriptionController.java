package com.stokr.strategy.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.strategy.dto.SubscriptionToggleResult;
import com.stokr.strategy.service.StrategySubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stokr.auth.security.StokrUserDetails;

import java.util.UUID;

@RestController
@RequestMapping("/api/strategies/catalog")
@RequiredArgsConstructor
@Tag(name = "Strategy subscriptions")
public class StrategySubscriptionController {

    private final StrategySubscriptionService subscriptionService;

    @PostMapping("/{definitionId}/subscription/toggle")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Enable/disable personal subscription to a catalog strategy")
    public ApiResponse<SubscriptionToggleResult> toggle(
            @PathVariable("definitionId") UUID definitionId,
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(subscriptionService.toggle(user.getId(), definitionId), CorrelationIdHolder.get());
    }
}
