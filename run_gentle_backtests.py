"""
Gentle, DB-gated backtest runner.

The earlier runner stalled the engine: too many concurrent jobs exhausted the
Hikari connection pool (NIFTY_CATCHUP issues thousands of per-bar queries), and
API status-polling timed out under load so concurrency wasn't actually capped.

This version gates concurrency by querying postgres DIRECTLY for the live RUNNING
count and never lets more than MAX_CONCURRENT backtests run at once. Runs 1-WEEK
first (fast), then 1-MONTH. Writes job ids to /tmp/gentle_jobs.json.
"""
import requests, json, time, sys, psycopg2

BASE = "http://localhost:8080"
DB_PW = "33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
MAX_CONCURRENT = 5
CUTOFF = "2026-06-14 03:45:00+00"   # only this run's jobs

conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform",
                        user="postgres", password=DB_PW)
conn.autocommit = True

def running_count():
    with conn.cursor() as c:
        c.execute("SELECT COUNT(*) FROM backtest_runs WHERE status IN ('RUNNING','QUEUED')")
        return c.fetchone()[0]

def login():
    for _ in range(6):
        try:
            r = requests.post(BASE + "/api/auth/login",
                              json={"principal": "vishnualgo@gmail.com", "password": "password123"}, timeout=20)
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
RANGES = [
    ("1_WEEK",  {"from": "2026-06-06T04:00:00Z", "to": "2026-06-12T10:00:00Z", "timezone": "Asia/Kolkata"}),
    ("1_MONTH", {"from": "2026-05-19T04:00:00Z", "to": "2026-06-12T10:00:00Z", "timezone": "Asia/Kolkata"}),
]

matrix = []
for rng_name, rng in RANGES:
    for strat in STRATEGIES:
        for sym in SYMBOLS:
            matrix.append((strat, sym, rng_name, rng))

print(f"total jobs: {len(matrix)}  max_concurrent={MAX_CONCURRENT}")
tok = login()
all_jobs = []

def submit(strat, sym, rng_name, rng, tok):
    payload = {
        "strategyKey": strat, "symbol": sym, "timeframe": "1m",
        "executionMode": "BACKTEST", "feeModel": "PERCENT_2_BPS",
        "slippageModel": "SPREAD_PROXY", "executionProfile": "SIMULATED_DEFAULT",
        "capital": 75000, "range": rng, "strategyParameters": {}, "seed": 42
    }
    r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok), timeout=20)
    if r.status_code == 401:
        tok2 = login()
        r = requests.post(BASE + "/api/backtest/jobs", json=payload, headers=hdrs(tok2), timeout=20)
    if r.status_code == 200:
        all_jobs.append({"jobId": str(r.json().get("data")),
                         "strategy": strat, "symbol": sym, "range": rng_name})
        return True
    print(f"  FAIL {strat}/{sym}/{rng_name}: {r.status_code} {r.text[:80]}")
    return False

for idx, (strat, sym, rng_name, rng) in enumerate(matrix):
    # gate on live RUNNING count
    while True:
        rc = running_count()
        if rc < MAX_CONCURRENT:
            break
        time.sleep(15)
    if (idx % 20) == 0:
        tok = login()
    submit(strat, sym, rng_name, rng, tok)
    time.sleep(1.0)
    if idx % 10 == 0:
        with open("/tmp/gentle_jobs.json", "w") as f:
            json.dump(all_jobs, f)
        print(f"  submitted {len(all_jobs)}/{len(matrix)} (running={running_count()})")

print(f"\nAll {len(all_jobs)} submitted. Draining...")
with open("/tmp/gentle_jobs.json", "w") as f:
    json.dump(all_jobs, f)

while True:
    rc = running_count()
    print(f"  draining: running={rc}")
    if rc == 0:
        break
    time.sleep(30)

print("ALL_DONE")
