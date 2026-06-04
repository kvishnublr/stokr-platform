package com.stokr.bootstrap.admin;

import com.stokr.bootstrap.feed.zerodha.IntradaySessionGapFillService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/feed")
@RequiredArgsConstructor
public class AdminFeedMaintenanceController {

    private final IntradaySessionGapFillService intradaySessionGapFillService;
    private final OrphanedSignalRedispatchService orphanedSignalRedispatchService;
    private final CatalogSignalRegenerateService catalogSignalRegenerateService;

    @PostMapping("/nifty-gap-fill")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, String>> triggerNiftyGapFill() {
        intradaySessionGapFillService.fillNiftySessionGapsIfNeeded("admin");
        return ApiResponse.ok(Map.of("status", "triggered"), CorrelationIdHolder.get());
    }

    @PostMapping("/redispatch-orphan-signals")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> redispatchOrphanSignals() {
        return ApiResponse.ok(orphanedSignalRedispatchService.redispatchSessionOrphans(java.time.Instant.now()),
                CorrelationIdHolder.get());
    }

    @PostMapping("/regenerate-catalog-signal")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> regenerateCatalogSignal(
            @RequestParam(required = false) UUID signalId,
            @RequestParam(defaultValue = "true") boolean preferLive) {
        return ApiResponse.ok(catalogSignalRegenerateService.regenerate(signalId, preferLive),
                CorrelationIdHolder.get());
    }
}
