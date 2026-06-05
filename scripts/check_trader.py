#!/usr/bin/env python3
import subprocess

def q(sql):
    r = subprocess.run(["docker","exec","-i","stokr-postgres","psql","-U","postgres","stokr_platform","-t","-A","-F","|"],
                       input=sql, capture_output=True, text=True, timeout=10)
    return r.stdout.strip()

print("=== risk_rules ALL ===")
print(q("SELECT r.user_id, r.max_open_positions, r.max_daily_loss, r.max_daily_trades, a.username FROM risk_rules r LEFT JOIN auth_users a ON r.user_id = a.id;"))

print("\n=== strategy_execution_configs ALL ===")
print(q("SELECT c.user_id, c.strategy_code, c.max_positions, c.max_trade_quantity, c.max_capital_per_trade, a.username FROM strategy_execution_configs c LEFT JOIN auth_users a ON c.user_id = a.id;"))

print("\n=== user_preferences_intraday ALL ===")
print(q("SELECT p.user_id, p.max_daily_trades, p.max_positions, p.risk_profile, a.username FROM user_preferences_intraday p LEFT JOIN auth_users a ON p.user_id = a.id;"))

print("\n=== Global config (stokr.risk) ===")
import urllib.request, json
BASE = "http://localhost:8080"
data = json.dumps({"principal":"admin","password":"admin123"}).encode()
req = urllib.request.Request(BASE+"/api/auth/login", data=data, headers={"Content-Type":"application/json"})
token = json.load(urllib.request.urlopen(req))["data"]["accessToken"]
# Try to find risk config
for path in ["/api/admin/risk-dashboard", "/api/admin/operations/snapshot", "/api/admin/signals/stats"]:
    req = urllib.request.Request(BASE+path, headers={"Authorization":"Bearer "+token})
    resp = json.load(urllib.request.urlopen(req))
    s = json.dumps(resp, default=str)
    if "max" in s.lower():
        print(f"\n--- {path} ---")
        print(s[:500])
