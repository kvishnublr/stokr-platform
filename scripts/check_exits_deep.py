#!/usr/bin/env python3
import subprocess
def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== EXIT CONFIGURATIONS ===")

# Check strategy_definitions config_json for exit params
print("\n--- strategy_definitions config_json ---")
rows = q("""SELECT strategy_key, 
       substring(config_json::text, 1, 500) as config
FROM strategy_definitions 
WHERE strategy_key IN ('PRE_OPEN_GAP_OI','GAP_FILL','ADV_CASH','INDEX_HUNT')
ORDER BY strategy_key;""")
for r in rows.split('\n'):
    if r.strip():
        print(r)

# Check app_config or system_config for exit parameters
print("\n--- app_config for exit-related settings ---")
print(q("""SELECT key, value
FROM app_config 
WHERE key LIKE '%exit%' OR key LIKE '%stoploss%' OR key LIKE '%pressure%' 
   OR key LIKE '%target%' OR key LIKE '%trailing%'
ORDER BY key;"""))

# Check strategy_execution_configs
print("\n--- strategy_execution_configs ---")
print(q("""SELECT sd.strategy_key, sec.config_key, sec.config_value, sec.definition_id
FROM strategy_execution_configs sec
JOIN strategy_definitions sd ON sd.id = sec.definition_id
ORDER BY sd.strategy_key, sec.config_key;"""))

# Check risk_rules for per-strategy exit limits
print("\n--- risk_rules ---")
print(q("""SELECT * FROM risk_rules;"""))

# Check exit-specific params in signal_pipeline_audit
print("\n--- Latest exit events from pipeline audit ---")
print(q("""SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, 
       stage_name, status, 
       substring(metadata_json::text, 1, 200) as meta
FROM signal_pipeline_audit
WHERE stage_name LIKE '%exit%' OR stage_name LIKE '%stoploss%' OR stage_name LIKE '%target%'
   OR stage_name LIKE '%pressure%' OR stage_name LIKE '%outcome%'
ORDER BY created_at DESC
LIMIT 10;"""))

# Check the cancel_order flow for pressure exits
print("\n--- Cancel/reject reasons for exits ---")
print(q("""SELECT o.state, o.rejection_reason, o.cancellation_reason,
       o.symbol, o.side, o.strategy_key,
       o.created_at AT TIME ZONE 'Asia/Kolkata' as ist
FROM oms_orders o
WHERE o.execution_mode='LIVE'
  AND o.created_at > '2026-06-08 00:00 IST'::timestamptz
ORDER BY o.created_at DESC
LIMIT 20;"""))
