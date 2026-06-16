"""
Re-run 1-month backtests for strategies that failed: ADV_CASH, NIFTY_CATCHUP, VWAP_BOUNCE, VWAP_SQUEEZE
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

# Only the 4 strategies missing 1-month data
STRATEGIES = ['ADV_CASH', 'NIFTY_CATCHUP', 'VWAP_BOUNCE', 'VWAP_SQUEEZE']

RANGE = {"from": "2026-05-19T04:00:00Z", "to": "2026-06-12T10:00:00Z"}

tok = login()
print("Logged in OK")

all_jobs = []

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
            "range": {"from": RANGE["from"], "to": RANGE["to"], "timezone": "Asia/Kolkata"},
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
                all_jobs.append({"jobId": str(job_id), "strategy": strat, "symbol": sym, "range": "1_MONTH"})
            else:
                print(f"  FAIL {strat}/{sym}: {r.status_code} {r.text[:80]}")
        except Exception as e:
            print(f"  EX {strat}/{sym}: {e}")
        time.sleep(0.1)

print(f"\n{len(all_jobs)} jobs queued. Polling...")
with open("/tmp/missing_1month_jobs.json", "w") as f:
    json.dump(all_jobs, f)

max_wait = 3600
interval = 30
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
                if status == "COMPLETED":
                    done += 1
                elif status in ("FAILED", "CANCELLED"):
                    failed += 1
                    done += 1
                else:
                    running += 1
        except:
            running += 1
    print(f"  t={waited}s: completed={done-failed}/{len(all_jobs)} failed={failed} running={running}")
    if done == len(all_jobs):
        break

print("\nCollecting run IDs...")
# Load existing completed runs
existing = json.load(open("/tmp/all_backtest_runs.json"))
completed_runs = [r for r in existing if r["status"] == "COMPLETED"]

tok = login()
for j in all_jobs:
    try:
        r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=15)
        if r.status_code == 200:
            d = r.json().get("data", {})
            completed_runs.append({
                "strategy": j["strategy"], "symbol": j["symbol"],
                "range": j["range"], "runId": d.get("runId"), "status": d.get("status")
            })
    except Exception as e:
        print(f"  Error {j['strategy']}/{j['symbol']}: {e}")

with open("/tmp/all_backtest_runs.json", "w") as f:
    json.dump(completed_runs, f, indent=2)
print(f"Total: {len(completed_runs)} runs saved")
print("Completed:", sum(1 for c in completed_runs if c["status"] == "COMPLETED"))
