package com.stokr.user.contact.web;

import com.stokr.auth.security.StokrUserDetails;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.user.contact.TraderContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trader/contact")
@RequiredArgsConstructor
public class TraderContactController {

    private final TraderContactService traderContactService;

    @GetMapping
    public ApiResponse<TraderContactService.ContactDto> get(
            @AuthenticationPrincipal StokrUserDetails user
    ) {
        return ApiResponse.ok(traderContactService.get(user.getId()), CorrelationIdHolder.get());
    }

    @PatchMapping
    public ApiResponse<TraderContactService.ContactDto> patch(
            @AuthenticationPrincipal StokrUserDetails user,
            @Valid @RequestBody TraderContactService.ContactPatchRequest request
    ) {
        return ApiResponse.ok(traderContactService.update(user.getId(), request), CorrelationIdHolder.get());
    }
}
