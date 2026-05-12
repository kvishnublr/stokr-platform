package com.stokr.user.broker.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.broker.ZerodhaConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trader/broker/zerodha")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TRADER','USER')")
public class TraderZerodhaController {

    private final ZerodhaConnectionService zerodhaConnectionService;

    @GetMapping({"/authorize-url", "/connect-url"})
    public ApiResponse<ZerodhaConnectionService.ZerodhaAuthorizeDto> authorize(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(zerodhaConnectionService.beginAuthorization(user.getId()), CorrelationIdHolder.get());
    }
}
