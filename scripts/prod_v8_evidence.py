#!/usr/bin/env python3
"""Collect V8 rollout evidence from production."""
import json
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd: str, timeout: int = 120) -> str:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    client.close()
    return (out + err).strip()


def psql(sql: str) -> str:
    q = sql.replace("'", "'\"'\"'")
    return run(
        f"docker exec stokr-postgres psql -U stokr -d stokr_platform -t -A -F'|' -c '{q}'"
    )


def main():
    evidence = {}

    evidence["git"] = run(f"cd {BASE} && git log -3 --oneline")
    evidence["health"] = run("curl -sf http://localhost:8080/actuator/health")
    evidence["containers"] = run("docker ps --filter name=stokr --format '{{.Names}}|{{.Status}}'")

    evidence["flyway_v78_v79"] = run(
        "docker logs stokr-api 2>&1 | grep -E 'V78|V79|confidence_engine|owner_and_lifecycle' | tail -15"
    )

    evidence["migration_columns"] = psql(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name='strategy_signals' AND column_name IN "
        "('confidence_score','probability','trade_quality','confidence_version',"
        "'confidence_breakdown_json','owner_type','lifecycle_status') ORDER BY 1"
    )

    evidence["baseline_today"] = psql(
        "SELECT COUNT(*)::text, "
        "COUNT(*) FILTER (WHERE outcome_status='TARGET_HIT')::text, "
        "COUNT(*) FILTER (WHERE outcome_status ILIKE '%STOP%')::text, "
        "COUNT(*) FILTER (WHERE outcome_status ILIKE '%PROTECT%' OR outcome_status ILIKE '%LIQUID%')::text, "
        "COUNT(*) FILTER (WHERE confidence_score IS NULL)::text, "
        "COUNT(*) FILTER (WHERE confidence_version='CONFIDENCE_V2')::text, "
        "ROUND(AVG(EXTRACT(EPOCH FROM (COALESCE(outcome_time, NOW()) - created_at)))::numeric,1)::text "
        "FROM strategy_signals WHERE deleted=false "
        "AND (created_at AT TIME ZONE 'Asia/Kolkata')::date = (NOW() AT TIME ZONE 'Asia/Kolkata')::date"
    )

    evidence["post_v8_signals"] = psql(
        "SELECT id::text, created_at::text, strategy_name, symbol, "
        "confidence_score::text, probability::text, trade_quality, confidence_version, "
        "owner_type, lifecycle_status, outcome_status, entry_price::text, target_price::text, stop_price::text "
        "FROM strategy_signals WHERE deleted=false AND signal_source IN ('LIVE','PAPER') "
        "AND created_at > '2026-05-29 17:35:00+00' ORDER BY created_at DESC LIMIT 15"
    )

    evidence["protection_since_deploy"] = psql(
        "SELECT COUNT(*)::text, "
        "COUNT(*) FILTER (WHERE exit_reason ILIKE '%VOLUME_VACUUM%' AND hold_seconds < 180 "
        "AND COALESCE(pressure_trigger,'') NOT IN ('HARD_STOP','FEED_PROTECTION','FEED_STALE'))::text, "
        "ROUND(AVG(hold_seconds)::numeric,1)::text, "
        "COUNT(*) FILTER (WHERE min_hold_bypassed)::text "
        "FROM strategy_exit_telemetry WHERE created_at > '2026-05-29 17:35:00+00'"
    )

    evidence["protection_recent"] = psql(
        "SELECT signal_id::text, hold_seconds::text, exit_category, "
        "LEFT(exit_reason,80), min_hold_bypassed::text, pressure_trigger "
        "FROM strategy_exit_telemetry ORDER BY created_at DESC LIMIT 8"
    )

    evidence["itc_sbin_prices"] = psql(
        "SELECT id::text, symbol, entry_price::text, target_price::text, stop_price::text, "
        "confidence_score::text, confidence_version, outcome_status "
        "FROM strategy_signals WHERE deleted=false AND symbol ILIKE ANY(ARRAY['%ITC%','%SBIN%']) "
        "ORDER BY created_at DESC LIMIT 6"
    )

    evidence["outcome_mfe"] = psql(
        "SELECT COUNT(*)::text, "
        "COUNT(*) FILTER (WHERE max_favorable_excursion IS NOT NULL)::text, "
        "COUNT(*) FILTER (WHERE max_adverse_excursion IS NOT NULL)::text "
        "FROM strategy_signals WHERE deleted=false AND outcome_status IS NOT NULL "
        "AND outcome_status NOT IN ('PENDING') AND created_at > NOW() - INTERVAL '7 days'"
    )

    evidence["api_startup_errors"] = run(
        "docker logs stokr-api --since 30m 2>&1 | grep -iE 'error|exception|Confidence missing|Flyway' | tail -20"
    )

    evidence["catalog_scan"] = run(
        "docker logs stokr-api --since 30m 2>&1 | grep -E 'catalog.scan|Confidence missing' | tail -15"
    )

    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
