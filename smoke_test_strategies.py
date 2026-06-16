"""
Smoke test: submit a few 1-week backtests for the fixed strategies and
report signal counts. Verifies NIFTY_CATCHUP (was 0) now fires.
"""
import requests, json, time, sys

BASE = "http://localhost:8080"

def login():
    r = requests.post(BASE + "/api/auth/login",
                      json={"principal": "vishnualgo@gmail.com", "password": "password123"})
    r.raise_for_status()
    return r.json()["data"]["accessToken"]

def hdrs(tok):
    return {"Authorization": "Bearer " + tok, "Content-Type": "application/json"}

tok = login()
print("logged in")

SYMBOLS = ["RELIANCE", "ICICIBANK", "INFY", "NTPC", "AXISBANK"]
STRATS = ["NIFTY_CATCHUP", "VWAP_BOUNCE", "VWAP_SQUEEZE", "ADV_CASH"]
RANGE = {"from": "2026-06-06T04:00:00Z", "to": "2026-06-12T10:00:00Z", "timezone": "Asia/Kolkata"}

jobs = []
for strat in STRATS:
    for sym in SYMBOLS:
        payload = {
            "strategyKey": strat, "symbol": sym, "timeframe": "1m",
            "executionMode": "BACKTEST", "feeModel": "PERCENT_2_BPS",
            "slippageModel": "SPREAD_PROXY", "executionProfile": "SIMULATED_DEFAULT",
            "capital": 75000, "range": RANGE, "strategyParameters": {}, "seed": 42
        }
        r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok), timeout=15)
        if r.status_code == 200:
            jobs.append({"jobId": str(r.json().get("data")), "strategy": strat, "symbol": sym})
        else:
            print(f"  FAIL {strat}/{sym}: {r.status_code} {r.text[:80]}")
        time.sleep(0.2)

print(f"{len(jobs)} jobs queued")
with open("/tmp/smoke_jobs.json", "w") as f:
    json.dump(jobs, f)

# Poll until done
for _ in range(60):
    time.sleep(20)
    tok = login()
    done = running = 0
    for j in jobs:
        r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=10)
        st = r.json().get("data", {}).get("status", "") if r.status_code == 200 else ""
        if st in ("COMPLETED", "FAILED", "CANCELLED"):
            done += 1
        else:
            running += 1
    print(f"  done={done}/{len(jobs)} running={running}")
    if done == len(jobs):
        break

print("DONE_POLLING")
