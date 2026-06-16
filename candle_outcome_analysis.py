import psycopg2
from decimal import Decimal

conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform", user="postgres", password="33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI=")
cur = conn.cursor()

# Get backtest signals from today's runs (last 4 hours)
cur.execute("""
    SELECT id, strategy_name, symbol, signal_type,
           entry_reference_price, stop_price, target_price,
           candle_timestamp, signal_validity_seconds
    FROM strategy_signals
    WHERE backtest_run_id IS NOT NULL
      AND created_at >= NOW() - INTERVAL '4 hours'
      AND strategy_name IN ('VWAP_BOUNCE', 'GAP_FILL')
      AND entry_reference_price IS NOT NULL
      AND stop_price IS NOT NULL
      AND target_price IS NOT NULL
    ORDER BY strategy_name, candle_timestamp
""")
signals = cur.fetchall()
print("Analyzing " + str(len(signals)) + " backtest signals with entry/sl/target...")

by_strategy = {}

for sig in signals:
    sig_id, strat, symbol, sig_type, entry, sl, target, ts, validity = sig
    entry = float(entry)
    sl = float(sl)
    target = float(target)
    validity_secs = validity if validity else 5400  # 90 min default

    # Get candles after signal timestamp within validity window
    cur.execute("""
        SELECT open_time, high_price, low_price, close_price
        FROM marketdata_candles
        WHERE symbol = %s
          AND timeframe = '1m'
          AND open_time > %s
          AND open_time <= %s + (%s || ' seconds')::interval
        ORDER BY open_time ASC
        LIMIT 200
    """, (symbol, ts, ts, str(validity_secs)))
    candles = cur.fetchall()

    if strat not in by_strategy:
        by_strategy[strat] = {
            "total": 0, "target_hit": 0, "sl_hit": 0, "expired": 0,
            "no_candles": 0, "pnl_pts": 0.0, "rr_list": []
        }
    s = by_strategy[strat]
    s["total"] += 1

    if not candles:
        s["no_candles"] += 1
        continue

    risk = abs(entry - sl)
    reward = abs(target - entry)
    if risk > 0:
        s["rr_list"].append(reward / risk)

    outcome = None
    for (ot, high, low, close) in candles:
        high = float(high)
        low = float(low)
        if sig_type == 'BUY':
            if high >= target:
                outcome = 'TARGET'
                break
            if low <= sl:
                outcome = 'SL'
                break
        else:  # SELL
            if low <= target:
                outcome = 'TARGET'
                break
            if high >= sl:
                outcome = 'SL'
                break

    if outcome == 'TARGET':
        s["target_hit"] += 1
        s["pnl_pts"] += reward
    elif outcome == 'SL':
        s["sl_hit"] += 1
        s["pnl_pts"] -= risk
    else:
        s["expired"] += 1

print("\n=== BACKTEST RESULTS (new code, 20 symbols, May 19 - Jun 12) ===\n")
for strat in sorted(by_strategy.keys()):
    s = by_strategy[strat]
    resolved = s["target_hit"] + s["sl_hit"]
    win_rate = round(s["target_hit"] / resolved * 100, 1) if resolved > 0 else 0
    avg_rr = round(sum(s["rr_list"]) / len(s["rr_list"]), 2) if s["rr_list"] else 0

    # At 25k/trade, avg risk = entry * sl_pct, but use points P&L as proxy
    # Count per-trade to estimate ₹ at 25k
    # avg_sl_pct depends on strategy; rough: pnl_pts / entry * 25000
    print("=== " + strat + " ===")
    print("  Total signals: " + str(s["total"]) + " (no candle data: " + str(s["no_candles"]) + ")")
    print("  Target hit: " + str(s["target_hit"]) + " | SL hit: " + str(s["sl_hit"]) + " | Expired: " + str(s["expired"]))
    print("  Win rate: " + str(win_rate) + "% | Setup R:R: " + str(avg_rr))
    exp_value = (win_rate/100 * avg_rr) - ((100-win_rate)/100) if avg_rr > 0 else 0
    print("  Expected value per R: " + str(round(exp_value, 3)) + " (" + ("POSITIVE" if exp_value > 0 else "NEGATIVE") + ")")
    print("  Raw P&L (price points): " + str(round(s["pnl_pts"], 2)))
    print()

# Per-symbol detail
print("=== Per-symbol (resolved trades only) ===")
cur.execute("""
    SELECT strategy_name, symbol, COUNT(*),
           COUNT(*) FILTER (WHERE signal_type='BUY') as buys,
           COUNT(*) FILTER (WHERE signal_type='SELL') as sells
    FROM strategy_signals
    WHERE backtest_run_id IS NOT NULL
      AND created_at >= NOW() - INTERVAL '4 hours'
      AND strategy_name IN ('VWAP_BOUNCE', 'GAP_FILL')
    GROUP BY strategy_name, symbol
    ORDER BY strategy_name, COUNT(*) DESC
""")
for row in cur.fetchall():
    print("  " + row[0] + "/" + row[1] + ": " + str(row[2]) + " signals (B:" + str(row[3]) + " S:" + str(row[4]) + ")")

cur.close()
conn.close()
