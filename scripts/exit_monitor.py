#!/usr/bin/env python3
"""Exit health monitor — run on prod via cron every 1-2 min.

Validates smart-exit outcomes are followed by outcome-exit OMS legs.
Logs to /var/log/stokr-exit-monitor.log (WARNING/ALERT = action needed).
Exit 0 = healthy, 1 = anomalies detected.
"""

from __future__ import annotations

import datetime
import json
import subprocess
import sys
from pathlib import Path

LOG = Path("/var/log/stokr-exit-monitor.log")
STATE = Path("/var/log/stokr-exit-monitor.state.json")

EXIT_OUTCOMES = (
    "TARGET_HIT",
    "STOPLOSS_HIT",
    "SL_HIT",
    "BREAKEVEN_EXIT",
    "PRESSURE_EXIT",
    "LIQUIDITY_PROTECTION",
    "FEED_PROTECTION",
    "TIME_EXIT",
)
OUTCOME_SQL_LIST = ",".join(f"'{o}'" for o in EXIT_OUTCOMES)


def q(sql: str) -> str:
    r = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            "stokr-postgres",
            "psql",
            "-U",
            "postgres",
            "stokr_platform",
            "-t",
            "-A",
            "-F",
            "|",
        ],
        input=sql,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip() or "psql failed")
    return r.stdout.strip()


def q_scalar(sql: str) -> int:
    raw = q(sql)
    if not raw:
        return 0
    return int(raw.split("|")[0].strip() or 0)


def docker_logs_since(minutes: int, pattern: str) -> list[str]:
    r = subprocess.run(
        [
            "docker",
            "logs",
            "stokr-api",
            f"--since={minutes}m",
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    lines = (r.stdout + r.stderr).splitlines()
    return [ln for ln in lines if pattern in ln]


def log(level: str, msg: str) -> None:
    ist = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=5, minutes=30)
    line = f"{ist.strftime('%Y-%m-%d %H:%M:%S')} {level} {msg}"
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line)


def main() -> int:
    issues: list[str] = []
    metrics: dict[str, object] = {"ts_utc": datetime.datetime.now(datetime.timezone.utc).isoformat()}

    # API health
    r = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--connect-timeout", "5", "http://127.0.0.1:8080/actuator/health"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    api_code = r.stdout.strip()
    metrics["api_health"] = api_code
    if api_code != "200":
        issues.append(f"API health HTTP {api_code}")
        log("WARNING", f"API health HTTP {api_code}")
    else:
        log("INFO", "API healthy")

    # Container health
    r2 = subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", "stokr-api"], capture_output=True, text=True, timeout=10)
    api_container = r2.stdout.strip()
    metrics["api_container"] = api_container
    if api_container not in ("healthy", ""):
        issues.append(f"stokr-api container {api_container}")
        log("WARNING", f"stokr-api container status={api_container}")

    # Smart exits (signal DB) last 30m
    signal_exits_30m = q_scalar(
        f"""SELECT count(*) FROM strategy_signals s
        WHERE s.deleted = false
          AND s.outcome_time > NOW() - INTERVAL '30 minutes'
          AND s.outcome_status IN ({OUTCOME_SQL_LIST})"""
    )
    metrics["signal_exits_30m"] = signal_exits_30m
    log("INFO", f"Signal terminal exits last 30m: {signal_exits_30m}")

    # OMS outcome-exit legs last 30m
    oms_exits_30m = q_scalar(
        """SELECT count(*) FROM oms_orders o
        WHERE o.deleted = false
          AND o.idempotency_key LIKE 'outcome-exit:%'
          AND o.created_at > NOW() - INTERVAL '30 minutes'"""
    )
    metrics["oms_outcome_exits_30m"] = oms_exits_30m
    log("INFO", f"OMS outcome-exit orders last 30m: {oms_exits_30m}")

    # Signals with filled entries but NO outcome-exit leg (last 2h) — primary regression detector
    missing_sql = f"""
    WITH recent AS (
      SELECT s.id, s.symbol, s.outcome_status, s.outcome_time, s.user_id
      FROM strategy_signals s
      WHERE s.deleted = false
        AND s.outcome_time > NOW() - INTERVAL '2 hours'
        AND s.outcome_status IN ({OUTCOME_SQL_LIST})
        AND COALESCE(s.test_trade, false) = false
        AND (s.signal_source IS NULL OR s.signal_source IN ('LIVE', 'PAPER'))
    ),
    with_entry AS (
      SELECT DISTINCT r.id, r.symbol, r.outcome_status, r.outcome_time
      FROM recent r
      WHERE EXISTS (
        SELECT 1 FROM oms_orders o
        WHERE o.deleted = false
          AND o.signal_id = r.id
          AND o.state IN ('FILLED', 'PARTIALLY_FILLED', 'ACCEPTED')
          AND upper(o.side) <> 'HOLD'
      )
      OR EXISTS (
        SELECT 1 FROM oms_orders paper
        JOIN oms_orders live ON live.id = paper.paired_order_id AND live.deleted = false
        WHERE paper.deleted = false
          AND paper.signal_id = r.id
          AND paper.state IN ('FILLED', 'PARTIALLY_FILLED', 'ACCEPTED')
          AND upper(paper.side) <> 'HOLD'
      )
    )
    SELECT we.id, we.symbol, we.outcome_status,
           we.outcome_time AT TIME ZONE 'Asia/Kolkata' AS outcome_ist
    FROM with_entry we
    WHERE NOT EXISTS (
      SELECT 1 FROM oms_orders o
      WHERE o.deleted = false
        AND o.idempotency_key LIKE 'outcome-exit:' || we.id::text || ':%'
    )
    ORDER BY we.outcome_time DESC
    LIMIT 15;
    """
    missing_rows = [ln for ln in q(missing_sql).splitlines() if ln.strip()]
    missing_cnt = len(missing_rows)
    metrics["missing_outcome_exit_legs"] = missing_cnt
    if missing_cnt > 0:
        sample = "; ".join(missing_rows[:5])
        issues.append(f"{missing_cnt} exited signals missing outcome-exit OMS leg")
        log("WARNING", f"{missing_cnt} signals with entries but no outcome-exit leg (sample): {sample}")
    else:
        log("INFO", "No missing outcome-exit legs (2h window)")

    # Log-based placement / failure counts (last 30m)
    placed_logs = docker_logs_since(30, "signal.outcome_exit.placed")
    failed_logs = docker_logs_since(30, "signal.outcome_exit.failed")
    dispatch_logs = docker_logs_since(30, "signal.outcome_exit.dispatch")
    smart_closed = docker_logs_since(30, "smart_exit.closed")
    metrics["log_placed_30m"] = len(placed_logs)
    metrics["log_failed_30m"] = len(failed_logs)
    metrics["log_dispatch_30m"] = len(dispatch_logs)
    metrics["log_smart_closed_30m"] = len(smart_closed)
    log("INFO", f"Logs 30m: dispatch={len(dispatch_logs)} placed={len(placed_logs)} failed={len(failed_logs)} smart_closed={len(smart_closed)}")
    if failed_logs:
        issues.append(f"{len(failed_logs)} signal.outcome_exit.failed in logs")
        log("WARNING", f"Recent failures: {failed_logs[-1][-240:]}")

    # If we had signal exits with entries, expect some dispatch activity (unless all flat/skipped)
    if signal_exits_30m > 0 and missing_cnt == 0 and len(dispatch_logs) == 0 and oms_exits_30m == 0:
        # could be pre-fix backlog only — only warn if new exits in last 10m
        recent_10m = q_scalar(
            f"""SELECT count(*) FROM strategy_signals s
            WHERE s.deleted = false
              AND s.outcome_time > NOW() - INTERVAL '10 minutes'
              AND s.outcome_status IN ({OUTCOME_SQL_LIST})"""
        )
        if recent_10m > 0:
            issues.append(f"{recent_10m} signal exits in 10m but no outcome-exit dispatch logs")
            log("WARNING", f"{recent_10m} exits in 10m without dispatch/placed logs — exit pipeline may be stuck")

    # Stuck RUNNING signals > 2h
    stuck = q_scalar(
        """SELECT count(*) FROM strategy_signals s
        WHERE s.deleted = false
          AND s.outcome_status IN ('RUNNING', 'PENDING', '')
          AND s.created_at < NOW() - INTERVAL '2 hours'"""
    )
    metrics["stuck_running_2h"] = stuck
    if stuck > 0:
        log("WARNING", f"{stuck} signals RUNNING/PENDING >2h")

    # Open LIVE entry legs without paired exit (rough ghost detector)
    live_open = q_scalar(
        """SELECT count(*) FROM oms_orders o
        WHERE o.deleted = false
          AND o.state = 'FILLED'
          AND o.execution_mode = 'LIVE'
          AND o.idempotency_key NOT LIKE 'outcome-exit:%'
          AND NOT EXISTS (
            SELECT 1 FROM oms_orders x
            WHERE x.deleted = false
              AND x.idempotency_key LIKE 'outcome-exit:%'
              AND x.symbol = o.symbol
              AND x.user_id = o.user_id
              AND x.created_at > o.created_at
          )"""
    )
    metrics["live_open_entries"] = live_open
    log("INFO", f"Open LIVE entry legs (no later outcome-exit on symbol): {live_open}")

    # Persist state for trend / external polling
    try:
        STATE.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    except OSError:
        pass

    if issues:
        log("ALERT", "; ".join(issues))
        return 1

    log("OK", "Exit pipeline healthy")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as ex:
        log("ERROR", str(ex))
        raise
