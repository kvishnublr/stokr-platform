import requests
import json
import time
import sys

BASE_URL = "http://localhost:8080"

def login():
    r = requests.post(BASE_URL + "/api/auth/login", json={
        "principal": "vishnualgo@gmail.com",
        "password": "password123"
    })
    if r.status_code != 200:
        print("Login failed: " + r.text)
        sys.exit(1)
    return r.json()["data"]["accessToken"]

def headers(token):
    return {"Authorization": "Bearer " + token, "Content-Type": "application/json"}

symbols = [
    'HDFCBANK', 'ICICIBANK', 'RELIANCE', 'INFY', 'AXISBANK',
    'KOTAKBANK', 'LT', 'BAJFINANCE', 'BHARTIARTL', 'HCLTECH',
    'MARUTI', 'ASIANPAINT', 'HINDUNILVR', 'NTPC', 'ONGC',
    'POWERGRID', 'COALINDIA', 'BPCL', 'DRREDDY', 'BAJAJFINSV'
]

from_dt = "2026-05-19T04:00:00Z"
to_dt   = "2026-06-12T10:00:00Z"

token = login()
print("Logged in OK")

# Enqueue all jobs
job_ids = []
for strategy in ['VWAP_BOUNCE', 'GAP_FILL']:
    print("\n=== Enqueuing " + strategy + " ===")
    for sym in symbols:
        payload = {
            "strategyKey": strategy,
            "symbol": sym,
            "timeframe": "1m",
            "executionMode": "BACKTEST",
            "feeModel": "PERCENT_2_BPS",
            "slippageModel": "SPREAD_PROXY",
            "executionProfile": "SIMULATED_DEFAULT",
            "capital": 500000,
            "range": {"from": from_dt, "to": to_dt, "timezone": "Asia/Kolkata"},
            "strategyParameters": {},
            "seed": 42
        }
        try:
            r = requests.post(BASE_URL + "/api/backtest/jobs", json=payload, headers=headers(token), timeout=15)
            if r.status_code == 200:
                job_id = r.json().get("data")
                job_ids.append({"jobId": str(job_id), "strategy": strategy, "symbol": sym})
                print("  " + strategy + "/" + sym + ": queued " + str(job_id))
            elif r.status_code == 401:
                token = login()
                r2 = requests.post(BASE_URL + "/api/backtest/jobs", json=payload, headers=headers(token), timeout=15)
                if r2.status_code == 200:
                    job_id = r2.json().get("data")
                    job_ids.append({"jobId": str(job_id), "strategy": strategy, "symbol": sym})
                    print("  " + strategy + "/" + sym + ": queued(re-auth) " + str(job_id))
                else:
                    print("  " + strategy + "/" + sym + ": FAIL " + str(r2.status_code) + " " + str(r2.text[:80]))
            else:
                err = r.text[:100]
                print("  " + strategy + "/" + sym + ": FAIL " + str(r.status_code) + " " + err)
        except Exception as e:
            print("  " + strategy + "/" + sym + ": EX " + str(e))
        time.sleep(0.2)

print("\n" + str(len(job_ids)) + " jobs queued. Waiting for completion...")

# Poll until all done
with open("/tmp/backtest_job_ids.json", "w") as f:
    json.dump(job_ids, f)

max_wait = 600
interval = 15
waited = 0
while waited < max_wait:
    time.sleep(interval)
    waited += interval
    token = login()
    done = 0
    failed = 0
    running = 0
    for j in job_ids:
        try:
            r = requests.get(BASE_URL + "/api/backtest/jobs/" + j["jobId"], headers=headers(token), timeout=10)
            if r.status_code == 200:
                status = r.json().get("data", {}).get("status", "")
                if status in ("COMPLETED", "FAILED", "CANCELLED"):
                    done += 1
                    if status == "FAILED":
                        failed += 1
                else:
                    running += 1
        except Exception:
            running += 1
    print("  t=" + str(waited) + "s: done=" + str(done) + "/" + str(len(job_ids)) + " running=" + str(running) + " failed=" + str(failed))
    if done == len(job_ids):
        break

print("\nAll jobs finished (or timed out). Collecting results...")

# Collect results
completed_run_ids = []
for j in job_ids:
    try:
        r = requests.get(BASE_URL + "/api/backtest/jobs/" + j["jobId"], headers=headers(token), timeout=10)
        if r.status_code == 200:
            d = r.json().get("data", {})
            run_id = d.get("runId")
            status = d.get("status")
            if run_id:
                completed_run_ids.append({"strategy": j["strategy"], "symbol": j["symbol"], "runId": run_id, "status": status})
    except Exception as e:
        print("  Error fetching " + j["jobId"] + ": " + str(e))

with open("/tmp/backtest_completed_runs.json", "w") as f:
    json.dump(completed_run_ids, f, indent=2)

print("Completed run IDs saved: " + str(len(completed_run_ids)))
for r in completed_run_ids[:5]:
    print("  " + r["strategy"] + "/" + r["symbol"] + " runId=" + str(r["runId"]) + " status=" + str(r["status"]))
if len(completed_run_ids) > 5:
    print("  ... and " + str(len(completed_run_ids) - 5) + " more")
