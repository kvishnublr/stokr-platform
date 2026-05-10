package com.stokr.user.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.dto.TraderOnboardingSummaryDto;
import com.stokr.user.service.TraderSelfStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trader/me")
@RequiredArgsConstructor
public class TraderSelfController {

    private final TraderSelfStatusService traderSelfStatusService;

    @GetMapping("/onboarding-summary")
    public ApiResponse<TraderOnboardingSummaryDto> onboardingSummary(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(traderSelfStatusService.onboardingSummary(user.getId()), CorrelationIdHolder.get());
    }
}
