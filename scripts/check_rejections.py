#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== Strategy definitions for S3/EARLY ===")
print(q("SELECT id, strategy_key, segment, asset_class, futures_strategy_enabled FROM strategy_definitions WHERE strategy_key IN ('S3_VWAP_RETEST', 'EARLY_BREAKOUT');"))

print("\n=== Universe groups ===")
print(q("SELECT id, name, description FROM strategy_universe_groups ORDER BY id LIMIT 20;"))

print("\n=== S3_VWAP_RETEST instance detail ===")
print(q("""SELECT si.id, si.user_id, si.definition_id, si.execution_mode, si.runtime_state,
       sd.strategy_key
FROM strategy_instances si
JOIN strategy_definitions sd ON si.definition_id = sd.id
WHERE sd.strategy_key IN ('S3_VWAP_RETEST', 'EARLY_BREAKOUT');"""))

print("\n=== Check if user 3333... is from a simulation/replay ===")
print(q("""SELECT table_name FROM information_schema.columns 
WHERE column_name = 'user_id' AND table_schema = 'public'
  AND table_name IN ('strategy_signals', 'oms_orders')
ORDER BY table_name;"""))

print("\n=== Check simulation data ===")
print(q("""SELECT id, strategy_key, user_id, is_simulation, simulation_run_id 
FROM oms_orders WHERE user_id = '33333333-3333-3333-3333-333333333333'
LIMIT 5;"""))

print("\n=== Check strategy_universe_symbols for what S3 trades ===")
print(q("""SELECT sus.id, sug.name as group_name, sus.symbol
FROM strategy_universe_symbols sus
JOIN strategy_universe_groups sug ON sus.group_id = sug.id
LIMIT 20;"""))

print("\n=== Check if there are any other users with 3333... pattern ===")
print(q("""SELECT id, username FROM auth_users WHERE id::text LIKE '3333%';"""))
