#!/usr/bin/env python3
"""Prod audit: INDEX_HUNT execution mode, signals, OMS, Redis, env."""
import paramiko

HOST = "173.249.55.84"
USER = "root"
PASS = "Temp1234.."
TRADER = "6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4"

QUERIES = [
    ("strategy_execution_config", f"""
SELECT strategy_key, user_id::text, enabled, execution_mode, live_enabled, paper_enabled
FROM strategy_execution_configs
WHERE deleted=false AND strategy_key='INDEX_HUNT'
ORDER BY user_id NULLS FIRST;
"""),
    ("strategy_definitions", """
SELECT strategy_key, execution_mode, validation_status, supports_live, supports_paper, enabled
FROM strategy_definitions WHERE strategy_key='INDEX_HUNT' AND deleted=false;
"""),
    ("instances", f"""
SELECT si.id::text, si.user_id::text, si.execution_mode, si.runtime_state, si.symbol, sd.strategy_key
FROM strategy_instances si
JOIN strategy_definitions sd ON sd.id=si.strategy_definition_id
WHERE sd.strategy_key='INDEX_HUNT' AND si.deleted=false
ORDER BY si.updated_at DESC LIMIT 10;
"""),
    ("bindings", """
SELECT sd.strategy_key, b.active, sug.group_key
FROM strategy_runtime_bindings b
JOIN strategy_definitions sd ON sd.id=b.strategy_catalog_id
JOIN strategy_universe_groups sug ON sug.id=b.universe_group_id
WHERE sd.strategy_key='INDEX_HUNT';
"""),
    ("signals_today", """
SELECT id::text, symbol, pipeline, outcome_status, created_at
FROM strategy_signals
WHERE created_at >= CURRENT_DATE AND strategy_name='INDEX_HUNT'
  AND symbol IN ('NESTLEIND','ICICIBANK')
ORDER BY created_at DESC LIMIT 20;
"""),
    ("oms_today", """
SELECT o.id::text, o.symbol, o.execution_mode, o.state,
       o.broker_external_order_id, o.reject_reason, o.created_at
FROM oms_orders o
WHERE o.deleted=false AND o.created_at >= CURRENT_DATE
  AND o.strategy_key='INDEX_HUNT'
ORDER BY o.created_at DESC LIMIT 15;
"""),
    ("oms_live_any", """
SELECT count(*) AS live_rows FROM oms_orders
WHERE deleted=false AND created_at >= CURRENT_DATE
  AND strategy_key='INDEX_HUNT' AND execution_mode='LIVE';
"""),
]

c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PASS, timeout=30)

def run(cmd, t=120):
    print("\n$", cmd[:200] + ("..." if len(cmd) > 200 else ""))
    _, o, e = c.exec_command(cmd, timeout=t)
    out = (o.read() + e.read()).decode("utf-8", "replace")
    print(out.strip() or "(empty)")

for name, sql in QUERIES:
    print("\n===", name, "===")
    run(f'docker exec stokr-postgres psql -U postgres -d stokr_platform -c "{sql.strip()}"')

run("docker exec stokr-redis redis-cli GET stokr:strategy:INDEX_HUNT:enabled")
run("docker exec stokr-api printenv | grep -E 'INDEX_HUNT|EXEC_MODE|LIVE_VALIDATED|PRIMARY_TRADER' || true")
run(f"docker exec stokr-postgres psql -U postgres -d stokr_platform -t -c \"SELECT id, principal FROM users WHERE id='{TRADER}'::uuid OR principal LIKE '%6343%';\"")
run("docker logs stokr-api 2>&1 | grep -E 'INDEX_HUNT|NESTLEIND|ICICIBANK' | grep -E 'catalog.scan|skip_broker|fanout|mode_resolved|live_blocked|both_mode|STRATEGY_DISABLED|downgraded' | tail -40")
c.close()
