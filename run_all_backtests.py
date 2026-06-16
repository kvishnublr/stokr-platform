"""
Full backtest runner: NIFTY_CATCHUP + VWAP_SQUEEZE + VWAP_CLOSE_RECLAIM + VWAP_BOUNCE + ADV_CASH
Periods: 1 week (Jun 6 - Jun 12) and 1 month (May 14 - Jun 12)
Capital: 75k, 5k/trade, 15 positions
"""
import requests
import json
import time
import sys

BASE = "http://localhost:8080"

def login():
    r = requests.post(BASE + "/api/auth/login",
                      json={"principal": "vishnualgo@gmail.com", "password": "password123"})
    if r.status_code != 200:
        print("Login failed:", r.text[:100])
        sys.exit(1)
    return r.json()["data"]["accessToken"]

def hdrs(tok):
    return {"Authorization": "Bearer " + tok, "Content-Type": "application/json"}

SYMBOLS = [
    'HDFCBANK', 'ICICIBANK', 'RELIANCE', 'INFY', 'AXISBANK',
    'KOTAKBANK', 'LT', 'BAJFINANCE', 'BHARTIARTL', 'HCLTECH',
    'MARUTI', 'ASIANPAINT', 'HINDUNILVR', 'NTPC', 'ONGC',
    'POWERGRID', 'COALINDIA', 'BPCL', 'DRREDDY', 'BAJAJFINSV'
]

STRATEGIES = ['NIFTY_CATCHUP', 'VWAP_SQUEEZE', 'VWAP_CLOSE_RECLAIM', 'VWAP_BOUNCE', 'ADV_CASH']

# Two date ranges
RANGES = {
    "1_WEEK":  {"from": "2026-06-06T04:00:00Z", "to": "2026-06-12T10:00:00Z"},
    "1_MONTH": {"from": "2026-05-19T04:00:00Z", "to": "2026-06-12T10:00:00Z"},
}

tok = login()
print("Logged in OK")

all_jobs = []  # {jobId, strategy, symbol, range_name}

for range_name, rng in RANGES.items():
    print(f"\n=== Enqueuing {range_name} ===")
    for strat in STRATEGIES:
        for sym in SYMBOLS:
            payload = {
                "strategyKey": strat,
                "symbol": sym,
                "timeframe": "1m",
                "executionMode": "BACKTEST",
                "feeModel": "PERCENT_2_BPS",
                "slippageModel": "SPREAD_PROXY",
                "executionProfile": "SIMULATED_DEFAULT",
                "capital": 75000,
                "range": {"from": rng["from"], "to": rng["to"], "timezone": "Asia/Kolkata"},
                "strategyParameters": {},
                "seed": 42
            }
            try:
                r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok), timeout=15)
                if r.status_code == 401:
                    tok = login()
                    r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok), timeout=15)
                if r.status_code == 200:
                    job_id = r.json().get("data")
                    all_jobs.append({"jobId": str(job_id), "strategy": strat, "symbol": sym, "range": range_name})
                else:
                    print(f"  FAIL {strat}/{sym}/{range_name}: {r.status_code} {r.text[:60]}")
            except Exception as e:
                print(f"  EX {strat}/{sym}/{range_name}: {e}")
            time.sleep(0.1)

print(f"\n{len(all_jobs)} jobs queued. Polling...")
with open("/tmp/all_backtest_jobs.json", "w") as f:
    json.dump(all_jobs, f)

max_wait = 1200
interval = 20
waited = 0
while waited < max_wait:
    time.sleep(interval)
    waited += interval
    tok = login()
    done = failed = running = 0
    for j in all_jobs:
        try:
            r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=10)
            if r.status_code == 200:
                status = r.json().get("data", {}).get("status", "")
                if status in ("COMPLETED", "FAILED", "CANCELLED"):
                    done += 1
                    if status != "COMPLETED": failed += 1
                else:
                    running += 1
        except:
            running += 1
    print(f"  t={waited}s: done={done}/{len(all_jobs)} running={running} failed={failed}")
    if done == len(all_jobs):
        break

print("\nCollecting run IDs...")
completed = []
for j in all_jobs:
    try:
        r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=10)
        if r.status_code == 200:
            d = r.json().get("data", {})
            run_id = d.get("runId")
            status = d.get("status")
            completed.append({
                "strategy": j["strategy"], "symbol": j["symbol"],
                "range": j["range"], "runId": run_id, "status": status
            })
    except:
        pass

with open("/tmp/all_backtest_runs.json", "w") as f:
    json.dump(completed, f, indent=2)
print(f"Saved {len(completed)} run IDs to /tmp/all_backtest_runs.json")
print("Completed:", sum(1 for c in completed if c["status"] == "COMPLETED"))
print("Still running:", sum(1 for c in completed if c["status"] == "RUNNING"))
print("Failed:", sum(1 for c in completed if c["status"] not in ("COMPLETED","RUNNING")))
