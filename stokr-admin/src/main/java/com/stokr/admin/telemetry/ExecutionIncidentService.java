package com.stokr.admin.telemetry;

import com.stokr.execution.safety.TradingKillSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Order-level incident rules. The infra evaluator (OperationalIncidentService) covers
 * Redis/DB/feed/queues; this one covers the thing the admin actually loses money on —
 * LIVE orders failing to reach the broker during market hours. Prior to this, 10 LIVE
 * rejections on 2026-06-12 (position caps, stale heartbeat) surfaced nowhere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionIncidentService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime NSE_OPEN = LocalTime.of(9, 15);
    private static final LocalTime NSE_CLOSE = LocalTime.of(15, 30);

    private final JdbcTemplate jdbcTemplate;
    private final TradingKillSwitchService tradingKillSwitchService;

    public List<Map<String, Object>> evaluate(Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            evaluateLiveFailures(now, out);
            ZonedDateTime ist = now.atZone(IST);
            if (isNseSession(ist)) {
                evaluateZeroFillRate(now, out);
                evaluateKillSwitchDuringSession(now, out);
            }
        } catch (Exception ex) {
            log.warn("execution_incidents.evaluate_failed err={}", ex.getMessage());
        }
        return out;
    }

    private void evaluateLiveFailures(Instant now, List<Map<String, Object>> out) {
        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT left(coalesce(reject_reason,'unknown'), 90) AS reason, count(*) AS cnt
                FROM oms_orders
                WHERE deleted = false AND execution_mode = 'LIVE'
                  AND state IN ('REJECTED','FAILED')
                  AND updated_at >= now() - interval '15 minutes'
                GROUP BY 1 ORDER BY cnt DESC LIMIT 5
                """);
        if (failures.isEmpty()) {
            return;
        }
        long total = failures.stream().mapToLong(r -> ((Number) r.get("cnt")).longValue()).sum();
        StringBuilder detail = new StringBuilder();
        for (Map<String, Object> r : failures) {
            if (detail.length() > 0) detail.append(" | ");
            detail.append(r.get("reason")).append(" x").append(r.get("cnt"));
        }
        out.add(incident("critical", "LIVE_ORDER_FAILURES",
                total + " LIVE order(s) rejected/failed in last 15 min",
                detail.toString(), "EXECUTION", now));
    }

    private void evaluateZeroFillRate(Instant now, List<Map<String, Object>> out) {
        Map<String, Object> stats = jdbcTemplate.queryForList("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE state IN ('FILLED','PARTIALLY_FILLED')) AS filled
                FROM oms_orders
                WHERE deleted = false
                  AND idempotency_key NOT LIKE 'outcome-exit:%'
                  AND created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Kolkata') AT TIME ZONE 'Asia/Kolkata'
                """).stream().findFirst().orElse(Map.of());
        long total = stats.get("total") instanceof Number n ? n.longValue() : 0;
        long filled = stats.get("filled") instanceof Number n ? n.longValue() : 0;
        if (total >= 5 && filled == 0) {
            out.add(incident("critical", "ZERO_ENTRY_FILL_RATE",
                    "No entry order has filled today (" + total + " attempted)",
                    "Check kill switch, position caps, broker session, strategy runtimes",
                    "EXECUTION", now));
        }
    }

    private void evaluateKillSwitchDuringSession(Instant now, List<Map<String, Object>> out) {
        if (tradingKillSwitchService.isActive()) {
            out.add(incident("critical", "KILL_SWITCH_DURING_SESSION",
                    "Kill switch is ENGAGED during NSE market hours — all entries are being rejected",
                    null, "RISK", now));
        }
    }

    private static boolean isNseSession(ZonedDateTime ist) {
        DayOfWeek d = ist.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = ist.toLocalTime();
        return !t.isBefore(NSE_OPEN) && !t.isAfter(NSE_CLOSE);
    }

    private static Map<String, Object> incident(
            String level, String code, String title, String detail, String subsystem, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("title", title);
        m.put("detail", detail);
        m.put("subsystem", subsystem);
        m.put("recoveryState", "OPEN");
        m.put("detectedAt", now.toString());
        return m;
    }
}
