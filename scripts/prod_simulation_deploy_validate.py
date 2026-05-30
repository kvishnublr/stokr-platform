#!/usr/bin/env python3
"""
Deploy verification + ordered simulation scenario runs on production.
Outputs JSON evidence with IDs, timestamps, tags.
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"
ADMIN_USER = "admin"
ADMIN_PASS = "password"
API = f"http://{HOST}:8080"

SCENARIOS_ORDER = [
    "BROKER_REJECT",
    "TARGET_HIT",
    "SL_HIT",
    "PROTECTION_EXIT",
]


def ssh(cmd: str, timeout: int = 600) -> str:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, stdout, stderr = c.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    c.close()
    return (out + err).strip()


def psql(sql: str) -> str:
    return ssh(
        "docker exec stokr-postgres psql -U postgres -d stokr_platform -t -A -F'|' -c "
        + repr(sql)
    )


_jwt_token: str | None = None


def login_jwt() -> str:
    global _jwt_token
    if _jwt_token:
        return _jwt_token
    data = json.dumps({"principal": ADMIN_USER, "password": ADMIN_PASS}).encode()
    req = urllib.request.Request(
        API + "/api/auth/login",
        data=data,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode())
    token = payload.get("data", {}).get("accessToken")
    if not token:
        raise RuntimeError(f"admin login failed: {payload}")
    _jwt_token = token
    return token


def api(method: str, path: str, body: dict | None = None) -> dict:
    token = login_jwt()
    data = None
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return {"http_error": e.code, "body": json.loads(raw)}
        except Exception:
            return {"http_error": e.code, "body": raw}


def evidence_for_run(run_id: str) -> dict:
    sigs = psql(
        f"SELECT id::text, created_at::text, updated_at::text, strategy_name, symbol, "
        f"signal_type, confidence_score::text, confidence_version, probability::text, "
        f"outcome_status, outcome_time::text, entry_price::text, target_price::text, stop_price::text, "
        f"realized_pnl::text, is_simulation::text, simulation_run_id::text, simulation_scenario, signal_source "
        f"FROM strategy_signals WHERE simulation_run_id='{run_id}'::uuid AND deleted=false ORDER BY created_at"
    )
    orders = psql(
        f"SELECT id::text, created_at::text, signal_id::text, state, execution_mode, "
        f"broker_vendor, reject_reason, is_simulation::text, simulation_run_id::text, simulation_scenario "
        f"FROM oms_orders WHERE simulation_run_id='{run_id}'::uuid AND deleted=false ORDER BY created_at"
    )
    execs = psql(
        f"SELECT e.id::text, e.created_at::text, e.order_id::text, e.filled_qty::text, e.avg_price::text, "
        f"e.execution_kind, e.is_simulation::text, e.simulation_run_id::text, e.simulation_scenario "
        f"FROM oms_executions e WHERE e.simulation_run_id='{run_id}'::uuid AND e.deleted=false ORDER BY e.created_at"
    )
    return {
        "signals_raw": sigs,
        "orders_raw": orders,
        "executions_raw": execs,
        "signal_count": len([l for l in sigs.splitlines() if l.strip()]) if sigs else 0,
        "order_count": len([l for l in orders.splitlines() if l.strip()]) if orders else 0,
        "execution_count": len([l for l in execs.splitlines() if l.strip()]) if execs else 0,
    }


def main():
    report = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "pre_deploy": {},
        "post_deploy_checks": {},
        "scenario_runs": [],
        "validate_release": None,
        "post_disable": {},
    }

    # --- Pre-deploy baseline (runtime should be disabled on old build too) ---
    report["pre_deploy"]["git_local"] = ssh(f"cd {BASE} && git log -1 --oneline 2>/dev/null || echo unknown")

    print("=== GIT PUSH expected before deploy — run deploy separately ===")

    # --- Post-deploy checks (caller runs after deploy) ---
    report["post_deploy_checks"]["git_head"] = ssh(f"cd {BASE} && git log -1 --oneline")
    report["post_deploy_checks"]["health"] = ssh("curl -sf http://localhost:8080/actuator/health")

    report["post_deploy_checks"]["flyway_v80_logs"] = ssh(
        "docker logs stokr-api 2>&1 | grep -E 'V80|simulation_safety' | tail -20"
    )
    report["post_deploy_checks"]["flyway_v80_schema"] = psql(
        "SELECT version, description, installed_on::text, success::text "
        "FROM flyway_schema_history WHERE version='80' ORDER BY installed_rank DESC LIMIT 1"
    )
    report["post_deploy_checks"]["migration_columns"] = psql(
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE column_name IN ('is_simulation','simulation_run_id','simulation_scenario') "
        "AND table_name IN ('strategy_signals','oms_orders','oms_executions','portfolio_positions','operational_audit_events') "
        "ORDER BY table_name, column_name"
    )
    report["post_deploy_checks"]["runtime_control_row"] = psql(
        "SELECT id::text, runtime_enabled::text, enabled_at::text, enabled_by::text "
        "FROM simulation_runtime_control WHERE id=1"
    )
    report["post_deploy_checks"]["startup_log"] = ssh(
        "docker logs stokr-api 2>&1 | grep -E 'SIMULATION MODE' | tail -5"
    )

    report["post_deploy_checks"]["admin_login"] = "ok"
    report["post_deploy_checks"]["runtime_status_api"] = api(
        "GET", "/api/admin/simulation/runtime/status"
    )

    # Enable simulation
    en = api("POST", "/api/admin/simulation/runtime/enable")
    report["post_deploy_checks"]["runtime_enable_response"] = en
    time.sleep(2)

    for scenario in SCENARIOS_ORDER:
        rt = api("GET", "/api/admin/simulation/runtime/status")
        if not (isinstance(rt.get("data"), dict) and rt["data"].get("enabled")):
            api("POST", "/api/admin/simulation/runtime/enable")
            time.sleep(1)
        print(f"Running scenario {scenario}...")
        run_resp = api("POST", "/api/admin/simulation/run", {
            "scenario": scenario,
            "symbol": "SBIN",
            "sessionBars": 120,
            "runProtectionMonitor": scenario == "PROTECTION_EXIT",
        })
        run_id = None
        if isinstance(run_resp.get("data"), dict):
            run_id = run_resp["data"].get("simulationRunId") or run_resp["data"].get("validation", {}).get("simulationRunId")
        entry = {
            "scenario": scenario,
            "api_response": run_resp,
            "run_id": run_id,
        }
        if run_id:
            time.sleep(3)
            entry["db_evidence"] = evidence_for_run(run_id)
            entry["effectiveness_simulation"] = api(
                "GET", "/api/admin/simulation/analytics?dataScope=SIMULATION"
            )
            entry["effectiveness_real"] = api(
                "GET", "/api/admin/simulation/analytics?dataScope=REAL"
            )
            entry["alpha_validation_real"] = api(
                "GET", "/api/admin/strategy-effectiveness/alpha-validation?dataScope=REAL"
            )
        report["scenario_runs"].append(entry)
        time.sleep(2)

    print("Running validate-release pack...")
    report["validate_release"] = api("POST", "/api/admin/simulation/validate-release")
    time.sleep(5)
    report["validate_release_all_runs"] = psql(
        "SELECT id::text, scenario, status, success::text, started_at::text, completed_at::text "
        "FROM simulation_runs WHERE deleted=false ORDER BY started_at DESC LIMIT 20"
    )

    dis = api("POST", "/api/admin/simulation/runtime/disable")
    report["post_disable"]["disable_response"] = dis
    report["post_disable"]["runtime_status"] = api("GET", "/api/admin/simulation/runtime/status")
    report["post_disable"]["startup_log_tail"] = ssh(
        "docker logs stokr-api 2>&1 | grep -E 'SIMULATION MODE' | tail -3"
    )

    out_path = "scripts/prod_simulation_evidence.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, default=str)
    print(json.dumps(report, indent=2, default=str))
    print(f"\nWROTE {out_path}")


if __name__ == "__main__":
    main()
