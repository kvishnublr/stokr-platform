"""
Analyze backtest results for all 5 strategies across 1-week and 1-month periods.
Capital: 75k, 5k/trade.
"""
import json
import psycopg2
from collections import defaultdict

DB_PW = "33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform",
                        user="postgres", password=DB_PW)
cur = conn.cursor()

runs = json.load(open("/tmp/all_backtest_runs.json"))
print(f"Total run entries: {len(runs)}")
print(f"Completed: {sum(1 for r in runs if r['status']=='COMPLETED')}")
print(f"Still RUNNING: {sum(1 for r in runs if r['status']=='RUNNING')}")
print(f"Failed/Other: {sum(1 for r in runs if r['status'] not in ('COMPLETED','RUNNING'))}")

completed_ids = [r['runId'] for r in runs if r.get('runId') and r['status'] == 'COMPLETED']
if not completed_ids:
    print("No completed runs yet.")
    cur.close(); conn.close(); exit(0)

placeholders = ','.join(['%s'] * len(completed_ids))

cur.execute(f"""
    SELECT
        strategy_name,
        symbol,
        signal_type,
        entry_reference_price,
        stop_price,
        target_price,
        hit_target,
        hit_stoploss,
        realized_pnl,
        candle_timestamp,
        backtest_run_id
    FROM strategy_signals
    WHERE backtest_run_id IN ({placeholders})
    ORDER BY strategy_name, candle_timestamp
""", completed_ids)
rows = cur.fetchall()
print(f"\nTotal signals loaded: {len(rows)}")

# Join with run metadata
run_map = {r['runId']: r for r in runs if r.get('runId')}

# Bucket by strategy + range
data = defaultdict(lambda: {
    "total":0, "targets":0, "sl_hits":0, "open":0,
    "pnl_pts":0.0, "rr_sum":0.0, "rr_count":0,
    "signals_5k":0, "pnl_5k":0.0
})

for row in rows:
    strat, sym, sig_type = row[0], row[1], row[2]
    entry = float(row[3]) if row[3] else None
    sl    = float(row[4]) if row[4] else None
    tgt   = float(row[5]) if row[5] else None
    hit_t = row[6]
    hit_s = row[7]
    pnl   = float(row[8]) if row[8] else None
    run_id = str(row[10])

    # Determine range from run metadata
    meta = run_map.get(run_id, {})
    range_name = meta.get('range', 'UNKNOWN')

    key = (strat, range_name)
    s = data[key]
    s["total"] += 1

    if hit_t:
        s["targets"] += 1
    elif hit_s:
        s["sl_hits"] += 1
    else:
        s["open"] += 1

    if pnl:
        s["pnl_pts"] += pnl

    if entry and sl and tgt and entry != sl:
        rr = abs(tgt - entry) / abs(entry - sl)
        s["rr_sum"] += rr
        s["rr_count"] += 1

    # ₹5k/trade P&L estimate
    if entry and sl and tgt and pnl is not None:
        risk_pct = abs(entry - sl) / entry if entry > 0 else 0
        reward_pct = abs(tgt - entry) / entry if entry > 0 else 0
        trade_capital = 5000
        if hit_t:
            trade_pnl = trade_capital * reward_pct
        elif hit_s:
            trade_pnl = -trade_capital * risk_pct
        else:
            trade_pnl = 0
        s["signals_5k"] += 1
        s["pnl_5k"] += trade_pnl

STRATEGIES = ['ADV_CASH', 'VWAP_BOUNCE', 'NIFTY_CATCHUP', 'VWAP_SQUEEZE', 'VWAP_CLOSE_RECLAIM']
RANGES = ['1_WEEK', '1_MONTH']

print("\n" + "="*80)
print("BACKTEST RESULTS — Capital ₹75k | ₹5k/trade | 15 max positions")
print("="*80)

for rng in RANGES:
    print(f"\n{'='*40}")
    print(f"PERIOD: {rng}")
    print(f"{'='*40}")
    total_pnl = 0
    for strat in STRATEGIES:
        key = (strat, rng)
        s = data.get(key)
        if not s or s["total"] == 0:
            print(f"\n  {strat}: NO DATA")
            continue
        resolved = s["targets"] + s["sl_hits"]
        win_rate = round(s["targets"] / resolved * 100, 1) if resolved > 0 else 0
        avg_rr   = round(s["rr_sum"] / s["rr_count"], 2) if s["rr_count"] > 0 else 0
        ev_r     = (win_rate/100 * avg_rr) - (1 - win_rate/100) if avg_rr > 0 else 0
        pnl_5k   = round(s["pnl_5k"], 0)
        total_pnl += pnl_5k

        print(f"\n  {strat}:")
        print(f"    Signals:  {s['total']} total | {resolved} resolved | {s['open']} open/expired")
        print(f"    Outcomes: {s['targets']} TARGET | {s['sl_hits']} SL | win rate {win_rate}%")
        print(f"    Avg R:R:  {avg_rr}x | EV/R: {round(ev_r,3)}")
        print(f"    P&L @₹5k: ₹{pnl_5k:+,.0f}")

    print(f"\n  ─────────────────────────────")
    print(f"  COMBINED TOTAL ({rng}): ₹{total_pnl:+,.0f}")

# Per-symbol detail for top performers
print("\n\n=== Per-symbol breakdown (1_MONTH, resolved only) ===")
cur.execute(f"""
    SELECT strategy_name, symbol,
           COUNT(*) as total,
           COUNT(*) FILTER (WHERE hit_target=true) as targets,
           COUNT(*) FILTER (WHERE hit_stoploss=true) as sl_hits,
           AVG(realized_pnl) as avg_pnl
    FROM strategy_signals
    WHERE backtest_run_id IN ({placeholders})
    GROUP BY strategy_name, symbol
    ORDER BY strategy_name, COUNT(*) DESC
""", completed_ids)
for r in cur.fetchall():
    resolved = (r[3] or 0) + (r[4] or 0)
    wr = f"{round(r[3]/resolved*100)}%" if resolved > 0 else "?"
    print(f"  {r[0]}/{r[1]}: {r[2]}s, T={r[3]}/SL={r[4]} wr={wr}")

cur.close()
conn.close()
