#!/usr/bin/env python3
"""Jun 3 prod audit: PRESSURE_EXIT outcomes."""
import json
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd: str, timeout: int = 120) -> str:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    client.close()
    return (out + err).strip()


def psql(sql: str, user: str = "postgres") -> str:
    q = sql.replace("'", "'\"'\"'")
    return run(
        f"docker exec stokr-postgres psql -U {user} -d stokr_platform -t -A -F'|' -c '{q}'"
    )


def discover_db_user() -> str:
    pg_env = run("docker exec stokr-postgres printenv POSTGRES_USER 2>/dev/null")
    candidates = []
    if pg_env and "error" not in pg_env.lower():
        candidates.append(pg_env.strip().splitlines()[0])
    api_db = run("docker exec stokr-api printenv DB_USER 2>/dev/null")
    if api_db and "error" not in api_db.lower():
        candidates.append(api_db.strip().splitlines()[0])
    candidates.extend(["postgres", "stokr", "stokr_platform"])
    seen = set()
    for u in candidates:
        if not u or u in seen:
            continue
        seen.add(u)
        r = psql("SELECT 1", user=u)
        if r.strip() == "1":
            return u
    return "postgres"


def main():
    db_user = discover_db_user()
    psql_fn = lambda sql: psql(sql, user=db_user)  # noqa: E731

    evidence = {}
    evidence["db_user"] = db_user
    evidence["today_ist"] = psql_fn(
        "SELECT (NOW() AT TIME ZONE 'Asia/Kolkata')::date::text, "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::time::text"
    )
    evidence["outcomes_by_type"] = psql_fn(
        "SELECT COALESCE(outcome_status,'NULL'), COUNT(*)::text, "
        "COUNT(*) FILTER (WHERE COALESCE(pipeline,'')='LIVE' OR signal_source='LIVE')::text "
        "FROM strategy_signals WHERE deleted=false "
        "AND (created_at AT TIME ZONE 'Asia/Kolkata')::date = "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::date "
        "GROUP BY 1 ORDER BY COUNT(*) DESC"
    )
    evidence["outcomes_by_strategy"] = psql_fn(
        "SELECT strategy_name, outcome_status, COUNT(*)::text "
        "FROM strategy_signals WHERE deleted=false "
        "AND (created_at AT TIME ZONE 'Asia/Kolkata')::date = "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::date "
        "AND outcome_status IS NOT NULL AND outcome_status NOT IN ('PENDING','RUNNING') "
        "GROUP BY 1,2 ORDER BY 1, COUNT(*) DESC"
    )
    evidence["pressure_exit_detail"] = psql_fn(
        "SELECT s.id::text, s.strategy_name, s.symbol, s.signal_type, "
        "ROUND(s.confidence_score::numeric,3)::text, "
        "s.entry_reference_price::text, s.stop_price::text, s.target_price::text, "
        "s.exit_price::text, s.realized_pnl::text, "
        "ROUND(EXTRACT(EPOCH FROM (s.outcome_time - s.created_at))/60,1)::text, "
        "LEFT(COALESCE(s.expiry_reason,''),120), "
        "t.pressure_score_at_exit::text, COALESCE(t.pressure_trigger,''), t.hold_seconds::text "
        "FROM strategy_signals s "
        "LEFT JOIN strategy_exit_telemetry t ON t.signal_id = s.id "
        "WHERE s.deleted=false AND s.outcome_status='PRESSURE_EXIT' "
        "AND (s.created_at AT TIME ZONE 'Asia/Kolkata')::date = "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::date "
        "ORDER BY s.outcome_time DESC"
    )
    evidence["sl_hit_today"] = psql_fn(
        "SELECT s.id::text, s.strategy_name, s.symbol, "
        "ROUND(s.confidence_score::numeric,3)::text, "
        "ROUND(EXTRACT(EPOCH FROM (s.outcome_time - s.created_at))/60,1)::text, "
        "LEFT(COALESCE(s.expiry_reason,''),80) "
        "FROM strategy_signals s "
        "WHERE s.deleted=false AND s.outcome_status IN ('STOPLOSS_HIT','SL_HIT') "
        "AND (s.created_at AT TIME ZONE 'Asia/Kolkata')::date = "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::date "
        "ORDER BY s.outcome_time DESC"
    )
    evidence["oms_fills_pressure"] = psql_fn(
        "SELECT s.id::text, "
        "COUNT(o.id) FILTER (WHERE o.state='FILLED')::text, "
        "COALESCE(BOOL_OR(o.state='FILLED'),false)::text "
        "FROM strategy_signals s "
        "LEFT JOIN oms_orders o ON o.signal_id = s.id AND o.deleted=false "
        "WHERE s.deleted=false AND s.outcome_status='PRESSURE_EXIT' "
        "AND (s.created_at AT TIME ZONE 'Asia/Kolkata')::date = "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::date "
        "GROUP BY s.id ORDER BY s.id"
    )
    evidence["telemetry_triggers"] = psql_fn(
        "SELECT COALESCE(pressure_trigger,''), exit_category, COUNT(*)::text, "
        "ROUND(AVG(hold_seconds)::numeric,0)::text, "
        "ROUND(AVG(pressure_score_at_exit)::numeric,3)::text "
        "FROM strategy_exit_telemetry "
        "WHERE (created_at AT TIME ZONE 'Asia/Kolkata')::date = "
        "(NOW() AT TIME ZONE 'Asia/Kolkata')::date "
        "GROUP BY 1,2 ORDER BY COUNT(*) DESC"
    )
    evidence["smart_exit_logs"] = run(
        "docker logs stokr-api --since 12h 2>&1 | "
        "grep -E 'smart_exit\\.(pressure|closed|min_hold)' | tail -30"
    )
    evidence["deploy_commit"] = run(
        "cd /opt/stokr/stokr-platform && git log -1 --oneline 2>/dev/null; "
        "docker exec stokr-api printenv STOKR_GIT_COMMIT 2>/dev/null"
    )
    evidence["env_smart_exit"] = run(
        "docker exec stokr-api printenv 2>/dev/null | "
        "grep -iE 'SMART_EXIT|PRESSURE_REVERSAL|EXHAUSTION|MIN_HOLD|LIFECYCLE' | sort"
    )
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
