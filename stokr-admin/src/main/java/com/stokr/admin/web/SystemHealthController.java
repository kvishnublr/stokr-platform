package com.stokr.admin.web;

import com.stokr.admin.service.AdminSystemHealthFixService;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Tag(name = "System Health")
public class SystemHealthController {

    private final AdminSystemHealthFixService systemHealthFixService;

    @PostMapping("/health/fix-all")
    @Operation(summary = "Execute ALL system health fixes")
    public ApiResponse<Map<String, Object>> fixAllSystemIssues(
            @RequestParam(required = false) UUID userId
    ) {
        UUID fixUserId = userId != null ? userId :
                java.util.UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");
        Map<String, Object> fixes = systemHealthFixService.executeAllFixes(fixUserId);
        return ApiResponse.ok(fixes, CorrelationIdHolder.get());
    }

    @PostMapping("/health/cleanup-ghost-symbols")
    @Operation(summary = "Cleanup specific ghost symbol positions")
    public ApiResponse<Map<String, Object>> cleanupGhostSymbols(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String symbols
    ) {
        UUID fixUserId = userId != null ? userId :
                java.util.UUID.fromString("6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4");
        List<String> symbolList = symbols != null ? Arrays.asList(symbols.split(",")) :
                List.of("HINDUNILVR", "ICICIBANK", "M&M");
        Map<String, Object> result = systemHealthFixService.cleanupGhostSymbols(fixUserId, symbolList);
        return ApiResponse.ok(result, CorrelationIdHolder.get());
    }
}
