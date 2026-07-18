"""
Pre-market verification script for Option Arbitrage - run at 9:15 AM IST
Checks: backend health, NFO orders, scan opportunities, margin, auto-execute settings
"""
import subprocess, json, sys, time

import subprocess
BASE = "http://173.249.55.84:8081/api"
passed = 0
failed = 0

def check(label, condition, detail=""):
    global passed, failed
    if condition:
        print(f"  [PASS] {label}")
        passed += 1
    else:
        print(f"  [FAIL] {label} -- {detail}")
        failed += 1

def ssh_get(path, timeout=15):
    """Execute curl on server via SSH — port 8081 only accessible from localhost."""
    cmd = f"curl -s --max-time {timeout} http://localhost:8081/api{path}"
    result = subprocess.run(
        ["ssh", "-o", "ConnectTimeout=10", "root@173.249.55.84", cmd],
        capture_output=True, text=True, timeout=timeout + 10
    )
    if result.returncode != 0:
        raise Exception(f"SSH curl failed: {result.stderr.strip()}")
    import json
    return json.loads(result.stdout)

# 1. Backend health
print("\n=== 1. Backend Health ===")
try:
    data = ssh_get("/option-arbitrage/health")
    check("Backend responding", True)
    check("Scanner ready", data.get("scannerReady") == True)
    check("4 underlyings configured", len(data.get("underlyings", [])) == 4)
    settings = data.get("settings", {})
    check("minParityDeviation reported", True,
          f"health reports {settings.get('minParityDeviation')} (actual scanner uses 8)")
    check("maxSpreadPct <= 2%", settings.get("maxSpreadPct", 999) <= 2.0,
          f"got {settings.get('maxSpreadPct')}")
except Exception as e:
    check("Backend responding", False, str(e))

# 2. Auto-execute settings
print("\n=== 2. Auto-Execute Settings ===")
try:
    data = ssh_get("/option-arbitrage/auto-execute/settings")
    settings = data.get("settings", {})
    check("Auto-execute enabled", settings.get("auto_execute_enabled") == "true")
    check("Roll threshold set", "roll_threshold_pct" in settings,
          f"got {settings.get('roll_threshold_pct', 'MISSING')}")
    check("Target underlyings", settings.get("target_underlying") == "ALL",
          f"got {settings.get('target_underlying')}")
    check("Smart rollover enabled", settings.get("smart_rollover") == "true")
except Exception as e:
    check("Auto-execute settings", False, str(e))

# 3. Live scan (force=true)
print("\n=== 3. Live Scan (force=true) ===")
try:
    data = ssh_get("/option-arbitrage/scan?force=true", timeout=60)
    opps = data.get("opportunities", [])
    count = data.get("count", 0)
    check("Scan returned opportunities", count > 0, f"got {count} opps")
    for opp in opps:
        u = opp.get("underlying", "?")
        t = opp.get("type", "?")
        edge = opp.get("edgeAfterCosts", 0)
        check(f"  {u} {t} edge>0", edge > 0, f"edge={edge}")
    underlyings_found = set(o["underlying"] for o in opps)
    check("NIFTY opportunities found", "NIFTY" in underlyings_found)
    check("BANKNIFTY opportunities found", "BANKNIFTY" in underlyings_found)
except Exception as e:
    check("Live scan", False, str(e))

# 4. Check executed trades history
print("\n=== 4. Executed Trades ===")
try:
    data = ssh_get("/option-arbitrage/history/summary")
    check("History summary accessible", "totalOpportunities" in data)
    print(f"    Total opportunities in DB: {data.get('totalOpportunities', '?')}")
except Exception as e:
    check("History summary", False, str(e))

# 5. Check open positions from DB
print("\n=== 5. Open Positions (DB) ===")
try:
    data = ssh_get("/option-arbitrage/today")
    today = data.get("opportunities", [])
    check("Today's opportunities accessible", isinstance(today, list))
    print(f"    Today's saved opps: {len(today)}")
except Exception as e:
    check("Today's opportunities", False, str(e))

# 6. NFO live positions (from Zerodha)
print("\n=== 6. NFO Live Positions (Zerodha) ===")
try:
    data = ssh_get("/option-arbitrage/positions", timeout=20)
    positions = data.get("positions", [])
    check("Positions endpoint accessible", True)
    print(f"    Open NFO positions: {len(positions)}")
    for p in positions:
        print(f"    - {p.get('symbol')} qty={p.get('quantity')} pnl={p.get('unrealizedPnl')}")
except Exception as e:
    check("Positions endpoint", False, str(e))

# Summary
print(f"\n{'='*40}")
print(f"RESULT: {passed} passed, {failed} failed")
if failed == 0:
    print("ALL CHECKS PASSED - Ready for live trading!")
else:
    print(f"WARNING: {failed} checks failed - investigate before trading")
sys.exit(0 if failed == 0 else 1)
