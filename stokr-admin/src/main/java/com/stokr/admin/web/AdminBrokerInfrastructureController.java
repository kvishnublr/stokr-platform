package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.BadRequestException;
import com.stokr.user.broker.PlatformMarketFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/broker-infrastructure")
@RequiredArgsConstructor
public class AdminBrokerInfrastructureController {

    private final PlatformMarketFeedService platformMarketFeedService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(platformMarketFeedService.infrastructureSnapshot(), CorrelationIdHolder.get());
    }

    @GetMapping("/{vendor}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> vendor(@PathVariable String vendor) {
        return ApiResponse.ok(platformMarketFeedService.vendorSnapshot(vendor), CorrelationIdHolder.get());
    }

    public record PauseBody(boolean paused) {
    }

    @PostMapping("/{vendor}/connect")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> connect(@PathVariable String vendor) {
        String v = vendor.trim().toUpperCase();
        if (!"ZERODHA".equals(v)) {
            throw new BadRequestException("Platform OAuth connect is implemented for ZERODHA in this build");
        }
        return ApiResponse.ok(platformMarketFeedService.beginZerodhaPlatformConnect(), CorrelationIdHolder.get());
    }

    @PostMapping("/{vendor}/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> refresh(@PathVariable String vendor) {
        return ApiResponse.ok(platformMarketFeedService.refreshFromKite(vendor), CorrelationIdHolder.get());
    }

    @PostMapping("/{vendor}/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> pause(@PathVariable String vendor, @RequestBody PauseBody body) {
        if (body == null) {
            throw new BadRequestException("body required");
        }
        return ApiResponse.ok(platformMarketFeedService.setIngestionPaused(vendor, body.paused()), CorrelationIdHolder.get());
    }

    @PostMapping("/{vendor}/resume")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> resume(@PathVariable String vendor) {
        return ApiResponse.ok(platformMarketFeedService.setIngestionPaused(vendor, false), CorrelationIdHolder.get());
    }

    @PostMapping("/{vendor}/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> disconnect(@PathVariable String vendor) {
        return ApiResponse.ok(platformMarketFeedService.disconnect(vendor), CorrelationIdHolder.get());
    }

    @PostMapping("/{vendor}/restart-ws")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> restartWs(@PathVariable String vendor) {
        return ApiResponse.ok(platformMarketFeedService.notImplemented("restart-ws"), CorrelationIdHolder.get());
    }

    @PostMapping("/{vendor}/resync")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> resync(@PathVariable String vendor) {
        return ApiResponse.ok(platformMarketFeedService.notImplemented("resync"), CorrelationIdHolder.get());
    }
}
