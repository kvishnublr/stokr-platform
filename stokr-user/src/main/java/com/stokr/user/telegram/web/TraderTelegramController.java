package com.stokr.user.telegram.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.telegram.TelegramVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trader/integrations/telegram")
@RequiredArgsConstructor
public class TraderTelegramController {

    private final TelegramVerificationService telegramVerificationService;

    @PostMapping("/verification-link")
    public ApiResponse<TelegramVerificationService.TelegramLinkDto> link(@AuthenticationPrincipal StokrUserDetails user) {
        return ApiResponse.ok(telegramVerificationService.createVerificationLink(user.getId()), CorrelationIdHolder.get());
    }
}
