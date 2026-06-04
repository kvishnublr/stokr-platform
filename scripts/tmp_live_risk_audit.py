#!/usr/bin/env python3
"""Audit LIVE mode display vs actual execution routing on prod."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."


def run(cmd, timeout=180):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


def psql(sql):
    # Use heredoc via bash to avoid quoting issues
    b64 = __import__("base64").b64encode(sql.encode()).decode()
    cmd = (
        f"echo {b64} | base64 -d | "
        "docker exec -i stokr-postgres psql -U postgres -d stokr_platform"
    )
    return run(cmd)


if __name__ == "__main__":
    queries = [
        ("exec_configs", """
SELECT sd.strategy_key,
       sec.execution_mode AS db_exec_mode,
       sec.live_enabled,
       sec.paper_enabled,
       sec.user_id IS NULL AS is_global
FROM strategy_definitions sd
LEFT JOIN strategy_execution_configs sec
  ON sec.strategy_key = sd.strategy_key AND sec.user_id IS NULL
WHERE sd.deleted = false AND sd.enabled = true
ORDER BY sd.strategy_key;
"""),
        ("strategy_defs_mode", """
SELECT strategy_key, execution_mode AS def_exec_mode,
       supports_live, supports_paper, validation_status, segment
FROM strategy_definitions
WHERE deleted = false AND enabled = true
ORDER BY strategy_key;
"""),
        ("currency_check", """
SELECT strategy_key FROM strategy_definitions
WHERE strategy_key IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION');
"""),
        ("flyway", """
SELECT version, description FROM flyway_schema_history
WHERE version IN ('81','82','83') ORDER BY version;
"""),
        ("signals_currency", """
SELECT s.strategy_name, COUNT(*) cnt, MAX(s.created_at) last_sig
FROM strategy_signals s
WHERE s.strategy_name IN ('USDINR_MOMENTUM','EURINR_MEAN_REVERSION')
  AND s.created_at >= NOW() - INTERVAL '7 days'
GROUP BY s.strategy_name;
"""),
    ]
    for name, sql in queries:
        print(f"\n=== {name} ===")
        print(psql(sql))

    print("\n=== api_env_modes ===")
    print(run("docker exec stokr-api printenv | grep -E '^STOKR_EXEC' | sort"))
