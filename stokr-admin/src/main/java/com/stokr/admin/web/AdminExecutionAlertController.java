package com.stokr.admin.web;

import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.execution.alert.ExecutionAlertLog;
import com.stokr.execution.alert.ExecutionAlertLogRepository;
import com.stokr.execution.safety.TradingKillSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surfaces the execution alert log (LIVE fills / rejections) and an at-a-glance
 * live-execution health summary so the admin sees broker-level failures the moment
 * they happen, instead of discovering them in postmortems.
 */
@RestController
@RequestMapping("/api/admin/execution-alerts")
@RequiredArgsConstructor
public class AdminExecutionAlertController {

    private static final Set<String> FAILURE_TYPES = Set.of("BROKER_REJECTED", "ORDER_REJECTED", "RISK_REJECTED");

    private final ExecutionAlertLogRepository alertLogRepository;
    private final TradingKillSwitchService tradingKillSwitchService;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Map<String, Object>>> recent(
            @RequestParam(value = "failuresOnly", defaultValue = "false") boolean failuresOnly) {
        List<ExecutionAlertLog> rows = failuresOnly
                ? alertLogRepository.findTop200ByDeletedFalseAndAlertTypeInOrderByCreatedAtDesc(FAILURE_TYPES)
                : alertLogRepository.findTop200ByDeletedFalseOrderByCreatedAtDesc();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ExecutionAlertLog row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("createdAt", row.getCreatedAt());
            m.put("alertType", row.getAlertType());
            m.put("strategyKey", row.getStrategyKey());
            m.put("symbol", row.getSymbol());
            m.put("orderId", row.getOrderId());
            m.put("userId", row.getUserId());
            m.put("payloadJson", row.getPayloadJson());
            out.add(m);
        }
        return ApiResponse.ok(out, CorrelationIdHolder.get());
    }

    /**
     * Live-execution health for the Alert Center strip: today's fill/rejection counts by mode,
     * time since last LIVE fill, recent failure count, kill-switch state.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("killSwitchActive", tradingKillSwitchService.isActive());
        out.put("recentLiveFailures15m", alertLogRepository.countByDeletedFalseAndAlertTypeInAndCreatedAtAfter(
                FAILURE_TYPES, Instant.now().minus(Duration.ofMinutes(15))));

        List<Map<String, Object>> todayByModeState = jdbcTemplate.queryForList("""
                SELECT execution_mode AS mode, state, count(*) AS cnt
                FROM oms_orders
                WHERE deleted = false
                  AND created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
                GROUP BY execution_mode, state
                ORDER BY cnt DESC
                """);
        out.put("todayOrdersByModeState", todayByModeState);

        Map<String, Object> lastLive = jdbcTemplate.queryForList("""
                SELECT max(updated_at) FILTER (WHERE state = 'FILLED')   AS last_live_fill,
                       max(updated_at) FILTER (WHERE state IN ('REJECTED','FAILED')) AS last_live_failure
                FROM oms_orders
                WHERE deleted = false AND execution_mode = 'LIVE'
                  AND created_at >= now() - interval '48 hours'
                """).stream().findFirst().orElse(Map.of());
        out.put("lastLiveFillAt", lastLive.get("last_live_fill"));
        out.put("lastLiveFailureAt", lastLive.get("last_live_failure"));
        return ApiResponse.ok(out, CorrelationIdHolder.get());
    }
}
