package com.stokr.user.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.dto.TraderExecutionModePreferenceDto;
import com.stokr.user.dto.TraderExecutionModeUpdateRequest;
import com.stokr.user.dto.TraderOnboardingSummaryDto;
import com.stokr.user.service.TraderExecutionModePreferenceService;
import com.stokr.user.service.TraderSelfStatusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trader/me")
@RequiredArgsConstructor
public class TraderSelfController {

    private final TraderSelfStatusService traderSelfStatusService;
    private final TraderExecutionModePreferenceService traderExecutionModePreferenceService;

    @GetMapping("/onboarding-summary")
    public ApiResponse<TraderOnboardingSummaryDto> onboardingSummary(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(traderSelfStatusService.onboardingSummary(user.getId()), CorrelationIdHolder.get());
    }

    @GetMapping("/execution-mode")
    public ApiResponse<TraderExecutionModePreferenceDto> executionMode(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(traderExecutionModePreferenceService.get(user.getId()), CorrelationIdHolder.get());
    }

    @PutMapping("/execution-mode")
    public ApiResponse<TraderExecutionModePreferenceDto> updateExecutionMode(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody TraderExecutionModeUpdateRequest request
    ) {
        boolean liveApproved = traderSelfStatusService.onboardingSummary(user.getId()).liveTradingApproved();
        return ApiResponse.ok(
                traderExecutionModePreferenceService.update(user.getId(), request.executionMode(), liveApproved),
                CorrelationIdHolder.get()
        );
    }
}
