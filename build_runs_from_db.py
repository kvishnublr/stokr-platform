"""
Build /tmp/all_backtest_runs.json from DB using job IDs from all_backtest_jobs.json
"""
import json
import psycopg2

DB_PW = "33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform",
                        user="postgres", password=DB_PW)
cur = conn.cursor()

jobs = json.load(open("/tmp/all_backtest_jobs.json"))
job_ids = [j["jobId"] for j in jobs]
job_map = {j["jobId"]: j for j in jobs}

placeholders = ','.join(['%s'] * len(job_ids))
cur.execute(f"""
    SELECT id::text, run_id::text, status
    FROM backtest_jobs
    WHERE id::text IN ({placeholders})
""", job_ids)

rows = cur.fetchall()
print(f"Found {len(rows)} job records in DB")

runs = []
for job_id, run_id, status in rows:
    meta = job_map.get(job_id, {})
    runs.append({
        "strategy": meta.get("strategy"),
        "symbol": meta.get("symbol"),
        "range": meta.get("range"),
        "runId": run_id,
        "status": status
    })

with open("/tmp/all_backtest_runs.json", "w") as f:
    json.dump(runs, f, indent=2)

completed = sum(1 for r in runs if r["status"] == "COMPLETED")
running = sum(1 for r in runs if r["status"] == "RUNNING")
failed = sum(1 for r in runs if r["status"] not in ("COMPLETED", "RUNNING"))
print(f"Completed: {completed}")
print(f"Running: {running}")
print(f"Failed/Other: {failed}")
print(f"Saved {len(runs)} entries to /tmp/all_backtest_runs.json")

cur.close()
conn.close()
