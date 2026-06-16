"""
Fresh backtests with the improved strategy logic.
5 strategies x 20 symbols x 2 ranges = 200 jobs.
Submitted in BATCHES that drain before the next batch, to avoid the async
thread-pool overflow that left jobs stuck RUNNING in earlier runs.
Saves job ids to /tmp/fresh_jobs.json.
"""
import requests, json, time, sys

BASE = "http://localhost:8080"

def login():
    for _ in range(5):
        try:
            r = requests.post(BASE + "/api/auth/login",
                              json={"principal": "vishnualgo@gmail.com", "password": "password123"}, timeout=15)
            if r.status_code == 200:
                return r.json()["data"]["accessToken"]
        except Exception as e:
            print("  login retry:", e)
        time.sleep(5)
    sys.exit("login failed")

def hdrs(tok):
    return {"Authorization": "Bearer " + tok, "Content-Type": "application/json"}

SYMBOLS = [
    'HDFCBANK', 'ICICIBANK', 'RELIANCE', 'INFY', 'AXISBANK',
    'KOTAKBANK', 'LT', 'BAJFINANCE', 'BHARTIARTL', 'HCLTECH',
    'MARUTI', 'ASIANPAINT', 'HINDUNILVR', 'NTPC', 'ONGC',
    'POWERGRID', 'COALINDIA', 'BPCL', 'DRREDDY', 'BAJAJFINSV'
]
STRATEGIES = ['ADV_CASH', 'NIFTY_CATCHUP', 'VWAP_BOUNCE', 'VWAP_SQUEEZE', 'VWAP_CLOSE_RECLAIM']
RANGES = {
    "1_WEEK":  {"from": "2026-06-06T04:00:00Z", "to": "2026-06-12T10:00:00Z", "timezone": "Asia/Kolkata"},
    "1_MONTH": {"from": "2026-05-19T04:00:00Z", "to": "2026-06-12T10:00:00Z", "timezone": "Asia/Kolkata"},
}

# Build the full job matrix
matrix = []
for rng_name, rng in RANGES.items():
    for strat in STRATEGIES:
        for sym in SYMBOLS:
            matrix.append((strat, sym, rng_name, rng))

print(f"total jobs to run: {len(matrix)}")
tok = login()

def running_count(jobs, tok):
    r = 0
    for j in jobs:
        try:
            resp = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=10)
            st = resp.json().get("data", {}).get("status", "") if resp.status_code == 200 else "RUNNING"
            if st not in ("COMPLETED", "FAILED", "CANCELLED"):
                r += 1
        except Exception:
            r += 1
    return r

all_jobs = []
BATCH = 20          # submit 20 at a time
MAX_INFLIGHT = 24   # wait until in-flight < this before next batch

i = 0
while i < len(matrix):
    # throttle: wait until in-flight is low
    while True:
        tok = login()
        inflight = running_count(all_jobs, tok)
        if inflight < MAX_INFLIGHT:
            break
        print(f"  throttle: inflight={inflight}, waiting...")
        time.sleep(20)

    batch = matrix[i:i + BATCH]
    for (strat, sym, rng_name, rng) in batch:
        payload = {
            "strategyKey": strat, "symbol": sym, "timeframe": "1m",
            "executionMode": "BACKTEST", "feeModel": "PERCENT_2_BPS",
            "slippageModel": "SPREAD_PROXY", "executionProfile": "SIMULATED_DEFAULT",
            "capital": 75000, "range": rng, "strategyParameters": {}, "seed": 42
        }
        try:
            r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok), timeout=15)
            if r.status_code == 401:
                tok = login()
                r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok), timeout=15)
            if r.status_code == 200:
                all_jobs.append({"jobId": str(r.json().get("data")),
                                 "strategy": strat, "symbol": sym, "range": rng_name})
            else:
                print(f"  FAIL {strat}/{sym}/{rng_name}: {r.status_code} {r.text[:80]}")
        except Exception as e:
            print(f"  EX {strat}/{sym}/{rng_name}: {e}")
        time.sleep(0.15)
    i += BATCH
    print(f"  submitted {len(all_jobs)}/{len(matrix)}")
    with open("/tmp/fresh_jobs.json", "w") as f:
        json.dump(all_jobs, f)

print(f"\nAll {len(all_jobs)} jobs submitted. Final drain...")
for _ in range(180):
    time.sleep(30)
    tok = login()
    done = failed = running = 0
    for j in all_jobs:
        try:
            r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=10)
            st = r.json().get("data", {}).get("status", "") if r.status_code == 200 else ""
            if st == "COMPLETED": done += 1
            elif st in ("FAILED", "CANCELLED"): failed += 1; done += 1
            else: running += 1
        except Exception:
            running += 1
    print(f"  completed={done-failed}/{len(all_jobs)} failed={failed} running={running}")
    if done == len(all_jobs):
        break

with open("/tmp/fresh_jobs.json", "w") as f:
    json.dump(all_jobs, f)
print("ALL_DONE")
