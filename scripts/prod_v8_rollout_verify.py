#!/usr/bin/env python3
"""V8 controlled rollout verification against production DB and admin API."""
import json
import paramiko
import urllib.request
import base64

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
ADMIN_USER = "admin"
ADMIN_PASS = "password"


def ssh_sql(sql: str) -> str:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    cmd = (
        "docker exec stokr-postgres psql -U stokr -d stokr_platform -t -A -F'|' -c "
        + repr(sql)
        + " 2>/dev/null || docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -F'|' -c "
        + repr(sql)
    )
    _, stdout, stderr = client.exec_command(cmd, timeout=120)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    client.close()
    if err.strip() and not out.strip():
        return err
    return out


def admin_get(path: str) -> dict:
    auth = base64.b64encode(f"{ADMIN_USER}:{ADMIN_PASS}".encode()).decode()
    url = f"http://{HOST}:8080{path}"
    req = urllib.request.Request(url, headers={"Authorization": f"Basic {auth}"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main():
    report = {"stages": {}}

    # Stage 1: migrations
    cols = ssh_sql(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_name='strategy_signals' AND column_name IN "
        "('confidence_score','probability','trade_quality','confidence_version',"
        "'confidence_breakdown_json','owner_type','lifecycle_status') ORDER BY 1;"
    )
    report["stages"]["1_migration_columns"] = cols.strip().splitlines()

    # Baseline today IST vs post-deploy new signals
    baseline = ssh_sql(
        "SELECT COUNT(*)::text, "
        "COUNT(*) FILTER (WHERE outcome_status='TARGET_HIT')::text, "
        "COUNT(*) FILTER (WHERE outcome_status LIKE '%STOP%')::text, "
        "COUNT(*) FILTER (WHERE outcome_status LIKE '%PROTECT%')::text, "
        "COUNT(*) FILTER (WHERE confidence_score IS NULL)::text, "
        "COUNT(*) FILTER (WHERE confidence_version='CONFIDENCE_V2')::text "
        "FROM strategy_signals WHERE deleted=false "
        "AND created_at >= (CURRENT_DATE AT TIME ZONE 'Asia/Kolkata')::timestamptz;"
    )
    report["stages"]["today_aggregate"] = baseline.strip()

    first10 = ssh_sql(
        "SELECT id::text, strategy_name, symbol, confidence_score::text, probability::text, "
        "trade_quality, confidence_version, "
        "CASE WHEN confidence_breakdown_json IS NOT NULL AND length(confidence_breakdown_json)>2 THEN 'Y' ELSE 'N' END, "
        "entry_price::text, target_price::text, stop_price::text, owner_type, lifecycle_status "
        "FROM strategy_signals WHERE deleted=false "
        "AND signal_source IN ('LIVE','PAPER') "
        "ORDER BY created_at DESC LIMIT 10;"
    )
    report["stages"]["4_first_10_signals"] = first10.strip().splitlines()

    protection = ssh_sql(
        "SELECT COUNT(*)::text, "
        "COUNT(*) FILTER (WHERE exit_reason ILIKE '%VOLUME_VACUUM%' AND hold_seconds < 180)::text, "
        "ROUND(AVG(hold_seconds)::numeric,1)::text "
        "FROM strategy_exit_telemetry "
        "WHERE created_at >= NOW() - INTERVAL '24 hours';"
    )
    report["stages"]["5_protection_24h"] = protection.strip()

    conf_check = ssh_sql(
        "SELECT COUNT(*)::text, COUNT(*) FILTER (WHERE confidence_score IS NULL)::text "
        "FROM strategy_signals WHERE deleted=false AND signal_source IN ('LIVE','PAPER') "
        "AND created_at > NOW() - INTERVAL '2 hours';"
    )
    report["stages"]["6_confidence_2h"] = conf_check.strip()

    try:
        report["stages"]["7_admin_protection"] = admin_get("/api/admin/diagnostics/protection")
    except Exception as ex:
        report["stages"]["7_admin_protection"] = {"error": str(ex)}

    try:
        report["stages"]["7_admin_strategy"] = admin_get("/api/admin/diagnostics/strategy")
    except Exception as ex:
        report["stages"]["7_admin_strategy"] = {"error": str(ex)}

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, _ = client.exec_command(
        f"cd {BASE} && git log -1 --oneline && curl -sf http://localhost:8080/actuator/health | head -c 500",
        timeout=60,
    )
    shell_out = stdout.read().decode(errors="replace").strip()
    client.close()
    report["prod_git_health"] = shell_out

    post_deploy = ssh_sql(
        "SELECT COUNT(*)::text, COUNT(*) FILTER (WHERE confidence_version='CONFIDENCE_V2')::text, "
        "COUNT(*) FILTER (WHERE confidence_score IS NULL)::text "
        "FROM strategy_signals WHERE deleted=false AND signal_source IN ('LIVE','PAPER') "
        "AND created_at > NOW() - INTERVAL '30 minutes';"
    )
    report["stages"]["post_deploy_30m"] = post_deploy.strip()

    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
