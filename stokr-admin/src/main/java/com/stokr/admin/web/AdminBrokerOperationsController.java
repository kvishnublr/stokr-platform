package com.stokr.admin.web;

import com.stokr.admin.service.AdminBrokerOrchestrationService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/brokers/orchestration")
@RequiredArgsConstructor
public class AdminBrokerOperationsController {

    private final AdminBrokerOrchestrationService adminBrokerOrchestrationService;

    public record UserIdBody(UUID userId) {
    }

    public record FeedPauseBody(UUID userId, String vendor, boolean paused) {
    }

    public record OutboundIpBody(UUID userId, String outboundIp) {
    }

    @PostMapping("/zerodha/test-session")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> zerodhaTestSession(@RequestBody UserIdBody body) {
        if (body == null || body.userId() == null) {
            throw new BadRequestException("userId is required");
        }
        return ApiResponse.ok(adminBrokerOrchestrationService.zerodhaTestSession(body.userId()), CorrelationIdHolder.get());
    }

    @PostMapping("/zerodha/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> zerodhaDisconnect(@RequestBody UserIdBody body) {
        if (body == null || body.userId() == null) {
            throw new BadRequestException("userId is required");
        }
        return ApiResponse.ok(adminBrokerOrchestrationService.zerodhaDisconnect(body.userId()), CorrelationIdHolder.get());
    }

    @PostMapping("/outbound-ip")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> setOutboundIp(@RequestBody OutboundIpBody body) {
        if (body == null || body.userId() == null) {
            throw new BadRequestException("userId is required");
        }
        return ApiResponse.ok(
                adminBrokerOrchestrationService.setOutboundIp(body.userId(), body.outboundIp()),
                CorrelationIdHolder.get()
        );
    }

    @GetMapping("/outbound-ips")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> listOutboundIps() {
        return ApiResponse.ok(
                adminBrokerOrchestrationService.listOutboundIps(),
                CorrelationIdHolder.get()
        );
    }

    @PostMapping("/feed-pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> feedPause(@RequestBody FeedPauseBody body) {
        if (body == null || body.userId() == null || body.vendor() == null || body.vendor().isBlank()) {
            throw new BadRequestException("userId and vendor are required");
        }
        return ApiResponse.ok(
                adminBrokerOrchestrationService.setFeedPaused(body.userId(), body.vendor(), body.paused()),
                CorrelationIdHolder.get()
        );
    }
}
