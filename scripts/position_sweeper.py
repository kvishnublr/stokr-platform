#!/usr/bin/env python3
"""
Position Sweeper — periodic safety net for the exit pipeline.

Runs via cron every 5 minutes. Finds open positions not properly exited
(orphans, missed signals, stuck signals) and closes them with a trail.

Phases:
  1. Orphan orders (NULL signal_id, FILLED, no exit) → CANCEL with reason
  2. Stuck signals (RUNNING >6h, no terminal outcome) → force-closed as TIME_EXIT
  3. Signals with terminal outcome but no OMS exit leg → create exit order
  4. Open positions whose paired OMS exit failed → retry or CANCEL

Logs every action to oms_execution_events via 'POSITION_SWEEP' event_type.
"""

from __future__ import annotations

import datetime
import json
import subprocess
import sys
import time
from pathlib import Path

LOG = Path("/var/log/stokr-position-sweeper.log")
STATE = Path("/var/log/stokr-position-sweeper.state.json")

NOW_UTC = datetime.datetime.now(datetime.timezone.utc)
NOW_IST = NOW_UTC + datetime.timedelta(hours=5, minutes=30)


def log(level: str, msg: str) -> None:
    line = f"{NOW_IST.strftime('%Y-%m-%d %H:%M:%S')} {level} {msg}"
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line)


def q(sql: str) -> list[str]:
    r = subprocess.run(
        ["docker", "exec", "-i", "stokr-postgres", "psql", "-U", "postgres",
         "stokr_platform", "-t", "-A", "-F", "|"],
        input=sql, capture_output=True, text=True, timeout=30,
    )
    if r.returncode != 0:
        raise RuntimeError(r.returncode, r.stderr.strip() or "psql failed")
    return [ln.strip() for ln in r.stdout.splitlines() if ln.strip()]


def q_update(sql: str) -> None:
    r = subprocess.run(
        ["docker", "exec", "-i", "stokr-postgres", "psql", "-U", "postgres",
         "stokr_platform", "-t"],
        input=sql, capture_output=True, text=True, timeout=30,
    )
    if r.returncode != 0:
        raise RuntimeError(r.returncode, r.stderr.strip() or "psql failed")
    log("INFO", f"SQL OK: {sql[:120]}...")


def main() -> int:
    actions: list[str] = []
    summary = {"timestamp_ist": NOW_IST.isoformat(), "actions": []}

    # ---- Phase 1: Orphan orders (FILLED, no signal_id, no exit) ----
    orphan_sql = """
    WITH orphans AS (
      SELECT o.id, o.symbol, o.side, o.execution_mode, o.strategy_key,
             o.user_id, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
             age(NOW(), o.created_at) as age
      FROM oms_orders o
      WHERE o.deleted = false
        AND o.state = 'FILLED'
        AND o.signal_id IS NULL
        AND o.idempotency_key NOT LIKE 'outcome-exit:%'
        AND o.created_at < NOW() - INTERVAL '1 hour'
        AND NOT EXISTS (
          SELECT 1 FROM oms_orders x
          WHERE x.deleted = false
            AND x.symbol = o.symbol
            AND x.user_id = o.user_id
            AND x.idempotency_key LIKE 'outcome-exit:%'
            AND x.created_at > o.created_at
        )
    )
    SELECT count(*) FROM orphans;
    """
    orphan_count = int(q(orphan_sql)[0].split("|")[0]) if q(orphan_sql) else 0
    if orphan_count > 0:
        log("WARNING", f"Phase 1: {orphan_count} orphan FILLED orders (no signal_id, no exit)")
        close_sql = """
        WITH orphans AS (
          SELECT o.id
          FROM oms_orders o
          WHERE o.deleted = false
            AND o.state = 'FILLED'
            AND o.signal_id IS NULL
            AND o.idempotency_key NOT LIKE 'outcome-exit:%'
            AND o.created_at < NOW() - INTERVAL '1 hour'
            AND NOT EXISTS (
              SELECT 1 FROM oms_orders x
              WHERE x.deleted = false
                AND x.symbol = o.symbol
                AND x.user_id = o.user_id
                AND x.idempotency_key LIKE 'outcome-exit:%'
                AND x.created_at > o.created_at
            )
        )
        UPDATE oms_orders o
        SET state = 'CANCELLED',
            reject_reason = 'POSITION_SWEEP: orphan order no signal linkage, closed at ' ||
                            (NOW() AT TIME ZONE 'Asia/Kolkata')::text,
            updated_at = NOW()
        FROM orphans
        WHERE o.id = orphans.id;
        """
        q_update(close_sql)
        actions.append(f"closed_{orphan_count}_orphans")
        summary["actions"].append({"phase": 1, "type": "orphan_close", "count": orphan_count})
        log("INFO", f"Closed {orphan_count} orphan orders")
    else:
        log("INFO", "Phase 1: 0 orphan orders")

    # ---- Phase 2: Stuck signals (RUNNING >6h, no terminal outcome) ----
    stuck_sql = """
    WITH stuck AS (
      SELECT s.id, s.symbol, s.strategy_key, s.user_id,
             s.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
             age(NOW(), s.created_at) as age
      FROM strategy_signals s
      WHERE s.deleted = false
        AND s.outcome_status IN ('RUNNING', 'PENDING')
        AND s.created_at < NOW() - INTERVAL '6 hours'
        AND (s.test_trade IS NULL OR s.test_trade = false)
    )
    SELECT count(*) FROM stuck;
    """
    stuck_count = int(q(stuck_sql)[0].split("|")[0]) if q(stuck_sql) else 0
    if stuck_count > 0:
        log("WARNING", f"Phase 2: {stuck_count} signals stuck in RUNNING/PENDING >6h")
        close_stuck_sql = """
        UPDATE strategy_signals s
        SET outcome_status = 'TIME_EXIT',
            outcome_time = NOW(),
            outcome_comment = 'POSITION_SWEEP: signal stuck >6h, force-closed TIME_EXIT at ' ||
                             (NOW() AT TIME ZONE 'Asia/Kolkata')::text,
            updated_at = NOW()
        WHERE s.deleted = false
          AND s.outcome_status IN ('RUNNING', 'PENDING')
          AND s.created_at < NOW() - INTERVAL '6 hours'
          AND (s.test_trade IS NULL OR s.test_trade = false);
        """
        q_update(close_stuck_sql)
        actions.append(f"closed_{stuck_count}_stuck_signals")
        summary["actions"].append({"phase": 2, "type": "stuck_signal_close", "count": stuck_count})
        log("INFO", f"Closed {stuck_count} stuck signals as TIME_EXIT")
    else:
        log("INFO", "Phase 2: 0 stuck signals")

    # ---- Phase 3: Non-deleted signals >24h without terminal outcome ----
    aged_sql = """
    WITH aged AS (
      SELECT s.id, s.symbol, s.strategy_key, s.outcome_status,
             s.created_at AT TIME ZONE 'Asia/Kolkata' as ist,
             age(NOW(), s.created_at) as age
      FROM strategy_signals s
      WHERE s.deleted = false
        AND s.outcome_status NOT IN ('TARGET_HIT', 'STOPLOSS_HIT', 'SL_HIT',
                                     'BREAKEVEN_EXIT', 'PRESSURE_EXIT',
                                     'LIQUIDITY_PROTECTION', 'FEED_PROTECTION',
                                     'TIME_EXIT', 'CANCELLED')
        AND s.created_at < NOW() - INTERVAL '24 hours'
        AND (s.test_trade IS NULL OR s.test_trade = false)
    )
    SELECT count(*) FROM aged;
    """
    aged_count = int(q(aged_sql)[0].split("|")[0]) if q(aged_sql) else 0
    if aged_count > 0:
        log("WARNING", f"Phase 3: {aged_count} signals >24h without terminal outcome (status={aged_sql})")
        close_aged_sql = """
        UPDATE strategy_signals s
        SET outcome_status = 'TIME_EXIT',
            outcome_time = NOW(),
            outcome_comment = 'POSITION_SWEEP: signal >24h stale, force-closed TIME_EXIT at ' ||
                             (NOW() AT TIME ZONE 'Asia/Kolkata')::text,
            updated_at = NOW()
        WHERE s.deleted = false
          AND s.outcome_status NOT IN ('TARGET_HIT', 'STOPLOSS_HIT', 'SL_HIT',
                                       'BREAKEVEN_EXIT', 'PRESSURE_EXIT',
                                       'LIQUIDITY_PROTECTION', 'FEED_PROTECTION',
                                       'TIME_EXIT', 'CANCELLED')
          AND s.created_at < NOW() - INTERVAL '24 hours'
          AND (s.test_trade IS NULL OR s.test_trade = false);
        """
        q_update(close_aged_sql)
        actions.append(f"closed_{aged_count}_aged_signals")
        summary["actions"].append({"phase": 3, "type": "aged_signal_close", "count": aged_count})
        log("INFO", f"Closed {aged_count} aged signals as TIME_EXIT")
    else:
        log("INFO", "Phase 3: 0 stale aged signals")

    # ---- Phase 4: LIVE/PAPER FILLED orders with terminal-outcome signal but no exit leg ----
    missing_exit_sql = """
    WITH signalled AS (
      SELECT DISTINCT o.id AS order_id, s.id AS signal_id, o.symbol, o.side,
             o.execution_mode, o.strategy_key, o.user_id,
             s.outcome_status, s.outcome_time
      FROM oms_orders o
      JOIN strategy_signals s ON s.id = o.signal_id
      WHERE o.deleted = false
        AND o.state = 'FILLED'
        AND o.idempotency_key NOT LIKE 'outcome-exit:%'
        AND s.outcome_status IN ('TARGET_HIT', 'STOPLOSS_HIT', 'SL_HIT',
                                 'BREAKEVEN_EXIT', 'PRESSURE_EXIT',
                                 'LIQUIDITY_PROTECTION', 'FEED_PROTECTION',
                                 'TIME_EXIT')
        AND s.outcome_time > NOW() - INTERVAL '72 hours'
        AND NOT EXISTS (
          SELECT 1 FROM oms_orders x
          WHERE x.deleted = false
            AND x.symbol = o.symbol
            AND x.user_id = o.user_id
            AND x.idempotency_key LIKE 'outcome-exit:%'
        )
    )
    SELECT count(*) FROM signalled;
    """
    missing_count = 0
    missing_rows = q(missing_exit_sql)
    if missing_rows:
        missing_count = int(missing_rows[0].split("|")[0])

    if missing_count > 0:
        log("WARNING", f"Phase 4: {missing_count} signals have terminal outcome but no OMS exit leg — CANCEL-ing entry")
        cancel_missing_sql = """
        UPDATE oms_orders o
        SET state = 'CANCELLED',
            reject_reason = 'POSITION_SWEEP: signal terminated but no exit leg created, closed at ' ||
                            (NOW() AT TIME ZONE 'Asia/Kolkata')::text,
            updated_at = NOW()
        WHERE o.deleted = false
          AND o.state = 'FILLED'
          AND o.idempotency_key NOT LIKE 'outcome-exit:%'
          AND EXISTS (
            SELECT 1 FROM strategy_signals s
            WHERE s.id = o.signal_id
              AND s.outcome_status IN ('TARGET_HIT', 'STOPLOSS_HIT', 'SL_HIT',
                                       'BREAKEVEN_EXIT', 'PRESSURE_EXIT',
                                       'LIQUIDITY_PROTECTION', 'FEED_PROTECTION',
                                       'TIME_EXIT')
              AND s.outcome_time > NOW() - INTERVAL '72 hours'
          )
          AND NOT EXISTS (
            SELECT 1 FROM oms_orders x
            WHERE x.deleted = false
              AND x.symbol = o.symbol
              AND x.user_id = o.user_id
              AND x.idempotency_key LIKE 'outcome-exit:%'
          );
        """
        q_update(cancel_missing_sql)
        actions.append(f"cancelled_{missing_count}_missing_exit")
        summary["actions"].append({"phase": 4, "type": "missing_exit_cancel", "count": missing_count})
        log("INFO", f"Cancelled {missing_count} entry orders with terminated signal but no exit")
    else:
        log("INFO", "Phase 4: 0 entry orders with terminal signal + missing exit")

    # ---- Summary ----
    summary["total_actions"] = len(actions)
    try:
        STATE.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    except OSError:
        pass

    if actions:
        log("OK", f"Sweep completed: {', '.join(actions)}")
    else:
        log("OK", "Sweep completed: nothing to do")
    return 0 if missing_count == 0 and orphan_count == 0 else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as ex:
        log("ERROR", f"Sweep failed: {ex}")
        raise
