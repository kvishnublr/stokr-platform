package com.stokr.admin.web;

import com.stokr.admin.service.PlatformReadinessService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/readiness")
@RequiredArgsConstructor
@Tag(name = "Admin readiness")
public class AdminReadinessController {

    private final PlatformReadinessService platformReadinessService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PlatformReadinessService.ReadinessSnapshot> readiness() {
        return ApiResponse.ok(platformReadinessService.snapshot(), CorrelationIdHolder.get());
    }
}
