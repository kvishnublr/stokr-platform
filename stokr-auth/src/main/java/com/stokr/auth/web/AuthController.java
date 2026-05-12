package com.stokr.auth.web;

import com.stokr.auth.dto.AuthResponse;
import com.stokr.auth.dto.LoginRequest;
import com.stokr.auth.dto.ForgotPasswordRequest;
import com.stokr.auth.dto.RefreshRequest;
import com.stokr.auth.dto.RegisterRequest;
import com.stokr.auth.dto.ResendVerificationResponse;
import com.stokr.auth.dto.ResetPasswordRequest;
import com.stokr.auth.security.StokrUserDetails;
import com.stokr.auth.service.AuthService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.UnauthorizedException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request), cid());
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request), cid());
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request), cid());
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return ApiResponse.ok(cid());
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok(cid());
    }

    @GetMapping("/verify-email")
    public ApiResponse<AuthResponse> verifyEmail(@RequestParam("token") String token) {
        return ApiResponse.ok(authService.verifyEmail(token), cid());
    }

    @PostMapping("/resend-verification")
    public ApiResponse<ResendVerificationResponse> resendVerification(@AuthenticationPrincipal StokrUserDetails principal) {
        if (principal == null) {
            throw new UnauthorizedException("Authentication required");
        }
        return ApiResponse.ok(authService.resendEmailVerification(principal.getId()), cid());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) RefreshBody refreshBody
    ) {
        String access = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            access = authorization.substring(7);
        }
        String refresh = refreshBody != null ? refreshBody.refreshToken() : null;
        authService.logout(access, refresh);
        return ApiResponse.ok(cid());
    }

    private static String cid() {
        return CorrelationIdHolder.get();
    }

    public record RefreshBody(String refreshToken) {
    }
}
