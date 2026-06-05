#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== strategy_instances for vishnualgo ===")
print(q("SELECT si.id, sd.strategy_key, si.enabled, si.execution_mode, si.runtime_state, si.symbol, si.started_at FROM strategy_instances si LEFT JOIN strategy_definitions sd ON si.definition_id = sd.id WHERE si.user_id = '6343e483-1d21-4fdf-ac0c-1ba19eaf2ff4';"))

print("\n=== ALL strategy_instances ===")
print(q("SELECT si.user_id, sd.strategy_key, si.enabled, si.execution_mode, si.runtime_state, si.symbol FROM strategy_instances si LEFT JOIN strategy_definitions sd ON si.definition_id = sd.id;"))

print("\n=== List all tables ===")
print(q("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE '%bind%' OR table_name LIKE '%user_strat%' OR table_name LIKE '%strat_user%' OR table_name LIKE '%subscription%' OR table_name LIKE '%instance%';"))

print("\n=== strategy_signals latest ===")
print(q("SELECT id, created_at, symbol, strategy_code, user_id, execution_mode, outcome_status FROM strategy_signals ORDER BY created_at DESC LIMIT 5;"))
