import json
import psycopg2

conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform", user="postgres")
cur = conn.cursor()

# Load run IDs from file
runs = json.load(open("/tmp/backtest_completed_runs.json"))
run_ids = [r["runId"] for r in runs if r.get("runId")]
print("Run IDs to analyze: " + str(len(run_ids)))

if not run_ids:
    # fallback: query today's backtest runs
    cur.execute("""
        SELECT DISTINCT backtest_run_id, strategy_name
        FROM strategy_signals
        WHERE backtest_run_id IS NOT NULL
          AND created_at >= NOW() - INTERVAL '3 hours'
          AND strategy_name IN ('VWAP_BOUNCE', 'GAP_FILL')
    """)
    rows = cur.fetchall()
    run_ids = [str(r[0]) for r in rows]
    print("Fallback run IDs from DB: " + str(len(run_ids)))

if not run_ids:
    print("No run IDs found.")
    exit(1)

placeholders = ",".join(["%s"] * len(run_ids))

# Get all signals for these runs
cur.execute("""
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
        candle_timestamp
    FROM strategy_signals
    WHERE backtest_run_id IN (""" + placeholders + """)
    ORDER BY strategy_name, candle_timestamp
""", run_ids)

rows = cur.fetchall()
print("Total signals found: " + str(len(rows)))

if not rows:
    print("No signals. Jobs may still be running or emitted 0 signals.")
    cur.execute("""
        SELECT strategy_name, COUNT(*)
        FROM strategy_signals
        WHERE backtest_run_id IS NOT NULL AND created_at >= NOW() - INTERVAL '4 hours'
        GROUP BY strategy_name
    """)
    for r in cur.fetchall():
        print("  Recent backtest signals: " + str(r))
    exit(0)

# Analyze per strategy
by_strategy = {}
for row in rows:
    strat = row[0]
    sym = row[1]
    sig_type = row[2]
    entry = float(row[3]) if row[3] else None
    sl = float(row[4]) if row[4] else None
    target = float(row[5]) if row[5] else None
    hit_tgt = row[6]
    hit_sl = row[7]
    pnl = float(row[8]) if row[8] else None

    if strat not in by_strategy:
        by_strategy[strat] = {"total": 0, "targets": 0, "sl_hits": 0, "open": 0, "pnl": 0.0, "rr_sum": 0.0, "rr_count": 0}
    s = by_strategy[strat]
    s["total"] += 1
    if hit_tgt:
        s["targets"] += 1
    elif hit_sl:
        s["sl_hits"] += 1
    else:
        s["open"] += 1
    if pnl:
        s["pnl"] += pnl
    if entry and sl and target and entry != sl:
        rr = abs(target - entry) / abs(entry - sl)
        s["rr_sum"] += rr
        s["rr_count"] += 1

print("\n=== BACKTEST RESULTS (new code, 20 symbols, May 19 - Jun 12) ===\n")
for strat, s in sorted(by_strategy.items()):
    resolved = s["targets"] + s["sl_hits"]
    win_rate = (s["targets"] / resolved * 100) if resolved > 0 else 0
    avg_rr = (s["rr_sum"] / s["rr_count"]) if s["rr_count"] > 0 else 0
    # Estimate P&L at 25k per trade: risk = entry * sl_pct, reward = entry * target_pct
    print("Strategy: " + strat)
    print("  Signals: " + str(s["total"]) + " | Resolved: " + str(resolved) + " | Open: " + str(s["open"]))
    print("  Targets hit: " + str(s["targets"]) + " | SL hit: " + str(s["sl_hits"]))
    print("  Win rate: " + str(round(win_rate, 1)) + "% | Avg R:R: " + str(round(avg_rr, 2)))
    print("  Realized P&L (raw): " + str(round(s["pnl"], 2)))
    print()

# Also show per-symbol breakdown for top/bottom performers
print("=== Per-symbol signal counts ===")
cur.execute("""
    SELECT strategy_name, symbol,
           COUNT(*) as total,
           COUNT(*) FILTER (WHERE hit_target=true) as targets,
           COUNT(*) FILTER (WHERE hit_stoploss=true) as sl_hits
    FROM strategy_signals
    WHERE backtest_run_id IN (""" + placeholders + """)
    GROUP BY strategy_name, symbol
    ORDER BY strategy_name, total DESC
""", run_ids)
for row in cur.fetchall():
    resolved = row[3] + row[4]
    wr = str(round(row[3]/resolved*100)) + "%" if resolved > 0 else "?"
    print("  " + row[0] + "/" + row[1] + ": " + str(row[2]) + " signals, " + str(row[3]) + "T/" + str(row[4]) + "SL wr=" + wr)

cur.close()
conn.close()
