#!/usr/bin/env python3
import paramiko

HOST = "173.249.55.84"
USER = "root"
PWD = "Temp1234.."
BASE = "/opt/stokr/stokr-platform"


def run(cmd, timeout=120):
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username=USER, password=PWD, timeout=30)
    _, o, e = c.exec_command(cmd, timeout=timeout)
    out = (o.read() + e.read()).decode(errors="replace")
    c.close()
    return out.strip()


def psql(sql):
    return run(
        'docker exec stokr-postgres psql -U postgres -d stokr_platform -c '
        + repr(sql)[1:-1]
    )


if __name__ == "__main__":
    checks = [
        ("git_status", f"cd {BASE} && git status -sb && git diff --stat"),
        ("v82_head", f"head -8 {BASE}/stokr-bootstrap/src/main/resources/db/migration/V82__currency_strategy_metadata.sql"),
        ("migrations", f"ls {BASE}/stokr-bootstrap/src/main/resources/db/migration/V8*.sql"),
        ("sig_cols", "SELECT column_name FROM information_schema.columns WHERE table_name='strategy_signals' ORDER BY ordinal_position"),
        ("inst_cols", "SELECT column_name FROM information_schema.columns WHERE table_name='strategy_instances' ORDER BY ordinal_position"),
        ("all_strategies", "SELECT strategy_key, segment, asset_class, enabled FROM strategy_definitions WHERE deleted=false ORDER BY strategy_key"),
        ("universe_groups", "SELECT id, group_key, segment, enabled FROM strategy_universe_groups ORDER BY group_key"),
        ("cds_symbols", "SELECT symbol, exchange FROM strategy_universe_symbols WHERE exchange='CDS' OR symbol ILIKE '%INR%' LIMIT 20"),
        ("runtime_health", "curl -sf http://127.0.0.1:8080/api/admin/runtime-health 2>/dev/null | head -c 3000 || echo FAIL"),
        ("exec_modes", "curl -sf http://127.0.0.1:8080/api/admin/execution-modes 2>/dev/null | head -c 2000 || echo FAIL"),
    ]
    for name, q in checks:
        print(f"\n=== {name} ===")
        if name.startswith("git") or name.startswith("v82") or name.startswith("migrations"):
            print(run(q))
        elif name.startswith("runtime") or name.startswith("exec"):
            print(run(q))
        else:
            print(psql(q))
