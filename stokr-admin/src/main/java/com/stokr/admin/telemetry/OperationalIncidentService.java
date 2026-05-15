package com.stokr.admin.telemetry;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based operational incidents derived from live telemetry maps (no decorative alerts).
 */
@Service
public class OperationalIncidentService {

    public List<Map<String, Object>> evaluate(
            Instant now,
            Map<String, Object> system,
            Map<String, Object> marketFreshness,
            Map<String, Object> replayInfra,
            Map<String, Object> rabbitQueues,
            Map<String, Object> brokerSessions
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<?, ?> redis = asMap(system.get("redis"));
        if ("DISCONNECTED".equals(String.valueOf(redis.get("status")))) {
            out.add(incident("critical", "REDIS_DOWN", "Redis unreachable", String.valueOf(redis.get("error")), "STORAGE", now));
        } else if (redis.get("pingMs") instanceof Number n && n.longValue() > 250) {
            out.add(incident("warn", "REDIS_SLOW", "Redis latency elevated", n + " ms", "STORAGE", now));
        }
        Map<?, ?> db = asMap(system.get("database"));
        if ("DISCONNECTED".equals(String.valueOf(db.get("status")))) {
            out.add(incident("critical", "DB_DOWN", "Database unreachable", String.valueOf(db.get("error")), "STORAGE", now));
        } else if (db.get("pingMs") instanceof Number n && n.longValue() > 500) {
            out.add(incident("warn", "DB_SLOW", "Database latency elevated", n + " ms", "STORAGE", now));
        }
        if ("STALE".equals(String.valueOf(marketFreshness.get("status")))) {
            out.add(incident(
                    "warn",
                    "MARKET_DATA_STALE",
                    "1m candle store is stale vs wall clock",
                    "lagSeconds=" + marketFreshness.get("latest1mLagSeconds"),
                    "MARKET_DATA",
                    now
            ));
        }
        if (Boolean.TRUE.equals(system.get("killSwitch"))) {
            out.add(incident("info", "KILL_SWITCH", "Global kill switch is ENABLED", null, "RISK", now));
        }
        if (Boolean.TRUE.equals(system.get("liveTradingArmed"))) {
            out.add(incident("info", "LIVE_TRADING_ARMED", "Live trading arm is ACTIVE", null, "EXECUTION", now));
        }
        Object jobsFailed = replayInfra.get("jobsFailed");
        if (jobsFailed instanceof Number n && n.longValue() > 0) {
            out.add(incident("warn", "REPLAY_FAILURES_PRESENT", "There are failed replay jobs in the database", "count=" + n, "REPLAY", now));
        }
        if (brokerSessions.get("vendors") instanceof Map<?, ?> vendors) {
            for (Map.Entry<?, ?> e : vendors.entrySet()) {
                Map<?, ?> v = asMap(e.getValue());
                if ("DISCONNECTED".equals(String.valueOf(v.get("status")))
                        && v.get("accountRows") instanceof Number cnt
                        && cnt.intValue() > 0) {
                    out.add(incident(
                            "warn",
                            "BROKER_SESSION_DEGRADED",
                            "Broker vendor has accounts but none connected",
                            String.valueOf(e.getKey()),
                            "BROKER",
                            now
                    ));
                }
            }
        }
        saturationScan(rabbitQueues, out, now);
        return out;
    }

    private static void saturationScan(Map<String, Object> rabbitQueues, List<Map<String, Object>> out, Instant now) {
        if (rabbitQueues == null) {
            return;
        }
        for (Map.Entry<String, Object> e : rabbitQueues.entrySet()) {
            Map<?, ?> props = asMap(e.getValue());
            Object depth = props.get("QUEUE_MESSAGE_COUNT");
            if (depth == null) {
                depth = props.get("queue_message_count");
            }
            if (depth instanceof String s) {
                try {
                    depth = Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    depth = null;
                }
            }
            if (depth instanceof Number n && n.longValue() > 2000) {
                out.add(incident(
                        "warn",
                        "QUEUE_SATURATED",
                        "Rabbit queue depth is high",
                        e.getKey() + "=" + n,
                        "MESSAGING",
                        now
                ));
            }
        }
    }

    private static Map<String, Object> incident(
            String level,
            String code,
            String title,
            String detail,
            String subsystem,
            Instant now
    ) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
