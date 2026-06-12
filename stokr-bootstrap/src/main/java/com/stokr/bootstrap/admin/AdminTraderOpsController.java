package com.stokr.bootstrap.admin;

import com.stokr.bootstrap.trader.TraderTerminalControlService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/trader")
@RequiredArgsConstructor
public class AdminTraderOpsController {

    private final TraderTerminalControlService traderTerminalControlService;

    @PostMapping("/{userId}/flatten-broker-positions")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> flattenBrokerPositions(@PathVariable UUID userId) {
        return ApiResponse.ok(traderTerminalControlService.flattenBrokerPositions(userId), CorrelationIdHolder.get());
    }
}
