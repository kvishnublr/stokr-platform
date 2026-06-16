"""
Collect completed run IDs from job IDs, wait for remaining jobs, then save runs.json
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

jobs = json.load(open("/tmp/all_backtest_jobs.json"))
print(f"Loaded {len(jobs)} jobs")

tok = login()
max_wait = 3600  # 1 hour
interval = 30
waited = 0

while waited < max_wait:
    running = queued = done = failed = 0
    for j in jobs:
        try:
            r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=15)
            if r.status_code == 401:
                tok = login()
                r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=15)
            if r.status_code == 200:
                status = r.json().get("data", {}).get("status", "")
                if status == "COMPLETED":
                    done += 1
                elif status == "FAILED":
                    failed += 1
                    done += 1
                else:
                    running += 1
        except Exception as e:
            running += 1
    print(f"  t={waited}s: completed={done-failed}/{len(jobs)} failed={failed} running={running}")
    if done == len(jobs):
        break
    time.sleep(interval)
    waited += interval

print("\nCollecting run IDs...")
completed = []
tok = login()
for j in jobs:
    try:
        r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=15)
        if r.status_code == 401:
            tok = login()
            r = requests.get(BASE + "/api/backtest/jobs/" + j["jobId"], headers=hdrs(tok), timeout=15)
        if r.status_code == 200:
            d = r.json().get("data", {})
            run_id = d.get("runId")
            status = d.get("status")
            completed.append({
                "strategy": j["strategy"], "symbol": j["symbol"],
                "range": j["range"], "runId": run_id, "status": status
            })
    except Exception as e:
        print(f"  Error {j['strategy']}/{j['symbol']}: {e}")

with open("/tmp/all_backtest_runs.json", "w") as f:
    json.dump(completed, f, indent=2)
print(f"Saved {len(completed)} entries to /tmp/all_backtest_runs.json")
print("Completed:", sum(1 for c in completed if c["status"] == "COMPLETED"))
print("Failed:", sum(1 for c in completed if c["status"] == "FAILED"))
print("Still running:", sum(1 for c in completed if c["status"] not in ("COMPLETED","FAILED","CANCELLED")))
