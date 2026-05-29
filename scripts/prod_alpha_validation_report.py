#!/usr/bin/env python3
"""Print alpha validation sprint summary from production DB (no UI)."""
import json
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def psql(sql: str) -> str:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    q = sql.replace("'", "'\"'\"'")
    cmd = f"docker exec stokr-postgres psql -U stokr -d stokr_platform -t -A -F'|' -c '{q}' 2>/dev/null || docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -F'|' -c '{q}'"
    _, stdout, _ = client.exec_command(cmd, timeout=120)
    out = stdout.read().decode(errors="replace")
    client.close()
    return out.strip()


def main():
    strategies = [
        "ADV_CASH", "GAP_FILL", "VWAP_BOUNCE", "NSE_SPIKE_DETECTION",
        "SECTOR_LAGGARD", "INDEX_HUNT", "S3_VWAP_RETEST", "S7_RANGE_FADE",
    ]
    report = {"strategies": {}, "platform": {}}

    for sk in strategies:
        row = psql(f"""
            SELECT COUNT(*)::text,
              COUNT(*) FILTER (WHERE outcome_status='TARGET_HIT')::text,
              COUNT(*) FILTER (WHERE outcome_status IN ('STOPLOSS_HIT','SL_HIT'))::text,
              COUNT(*) FILTER (WHERE outcome_status IN ('PRESSURE_EXIT','LIQUIDITY_PROTECTION','BREAKEVEN_EXIT','FEED_PROTECTION'))::text,
              COALESCE(SUM(realized_pnl),0)::text,
              ROUND(AVG(EXTRACT(EPOCH FROM (COALESCE(outcome_time, updated_at)-created_at))) FILTER (WHERE outcome_status NOT IN ('PENDING','RUNNING'))::numeric,1)::text,
              COUNT(*) FILTER (WHERE confidence_score IS NOT NULL)::text
            FROM strategy_signals
            WHERE deleted=false AND is_test_trade=false AND backtest_run_id IS NULL
              AND (signal_source IS NULL OR signal_source IN ('LIVE','PAPER'))
              AND strategy_name='{sk}'
              AND created_at >= NOW() - INTERVAL '30 days'
        """)
        parts = row.split("|") if row else ["0"] * 7
        report["strategies"][sk] = {
            "signals": parts[0] if len(parts) > 0 else "0",
            "target_hits": parts[1] if len(parts) > 1 else "0",
            "stop_hits": parts[2] if len(parts) > 2 else "0",
            "protection_exits": parts[3] if len(parts) > 3 else "0",
            "realized_pnl": parts[4] if len(parts) > 4 else "0",
            "avg_hold_sec": parts[5] if len(parts) > 5 else "0",
            "confidence_populated": parts[6] if len(parts) > 6 else "0",
        }

    prot = psql("""
        SELECT COUNT(*)::text,
          COUNT(*) FILTER (WHERE exit_reason ILIKE '%VOLUME_VACUUM%')::text,
          ROUND(AVG(hold_seconds)::numeric,1)::text
        FROM strategy_exit_telemetry
        WHERE created_at >= NOW() - INTERVAL '30 days'
    """)
    report["platform"]["protection_telemetry_30d"] = prot

    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
