package com.stokr.user.whatsapp.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.whatsapp.WhatsappVerificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trader/integrations/whatsapp")
@RequiredArgsConstructor
public class TraderWhatsappController {

    private final WhatsappVerificationService whatsappVerificationService;

    @PostMapping("/send-otp")
    public ApiResponse<Void> send(@AuthenticationPrincipal StokrUserDetails user) {
        whatsappVerificationService.sendOtp(user.getId());
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    @PostMapping("/verify")
    public ApiResponse<Void> verify(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody VerifyBody body
    ) {
        whatsappVerificationService.verify(user.getId(), body.otp());
        return ApiResponse.ok(CorrelationIdHolder.get());
    }

    public record VerifyBody(@NotBlank String otp) {
    }
}
