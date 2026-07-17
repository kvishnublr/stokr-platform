#!/usr/bin/env python3
import subprocess, json

def api(cmd):
    r = subprocess.run(['ssh', '-o', 'StrictHostKeyChecking=no', 'root@173.249.55.84', cmd],
                       capture_output=True, text=True, timeout=30)
    return r.stdout.strip()

print("=== BACKEND HEALTH ===")
print(api("curl -s http://localhost:8081/api/option-arbitrage/health"))

print("\n=== TODAY (NIFTY) ===")
print(api("curl -s 'http://localhost:8081/api/option-arbitrage/today?underlying=NIFTY'"))

print("\n=== HISTORY DATES ===")
print(api("curl -s 'http://localhost:8081/api/option-arbitrage/history/dates'"))

print("\n=== HISTORY SUMMARY ===")
print(api("curl -s 'http://localhost:8081/api/option-arbitrage/history/summary'"))

print("\n=== LIVE PRICES BATCH (should be empty after hours) ===")
print(api("curl -s 'http://localhost:8081/api/option-arbitrage/live-prices-batch?underlying=NIFTY'"))

print("\n=== CALENDAR SPREAD ===")
r = api("curl -s --max-time 30 'http://localhost:8081/api/option-arbitrage/calendar-spread?underlying=NIFTY'")
print(r[:300] if r else "TIMEOUT")

print("\n=== VOL SURFACE ===")
r2 = api("curl -s --max-time 30 'http://localhost:8081/api/option-arbitrage/vol-surface?underlying=NIFTY'")
print(r2[:300] if r2 else "TIMEOUT")

print("\n=== STRATEGIES ===")
print(api("curl -s 'http://localhost:8081/api/strategies'")[:300])

print("\n=== DEPLOYMENTS ===")
print(api("curl -s 'http://localhost:8081/api/deployments'")[:300])

print("\n=== SIGNALS ===")
print(api("curl -s 'http://localhost:8081/api/signals?userId=1'")[:200])

print("\n=== FRONTEND ===")
print(api("curl -s -o /dev/null -w '%{http_code}' https://stokr.in/"))
