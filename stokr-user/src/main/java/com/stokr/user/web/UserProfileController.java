package com.stokr.user.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.dto.UserProfileDto;
import com.stokr.user.dto.UserProfileUpdateRequest;
import com.stokr.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ApiResponse<UserProfileDto> get(@AuthenticationPrincipal StokrUserDetails user) {
        UserProfileDto dto = userProfileService.ensureProfile(user.getId());
        return ApiResponse.ok(dto, CorrelationIdHolder.get());
    }

    @PutMapping
    public ApiResponse<UserProfileDto> update(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody UserProfileUpdateRequest request
    ) {
        return ApiResponse.ok(userProfileService.update(user.getId(), request), CorrelationIdHolder.get());
    }
}
