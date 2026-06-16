"""
Proper backtest P&L analysis using strategy_signals + subsequent candles.
For each signal: scan subsequent 1m bars to determine if target or SL was hit first.
Capital: 75k, 5k per trade, 15 max concurrent positions.
Takes only the LATEST completed run per (strategy, symbol, range) to avoid duplicates.
"""
import psycopg2
import psycopg2.extras
from collections import defaultdict
from decimal import Decimal

DB_PW = "33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
conn = psycopg2.connect(host="localhost", port=5432, dbname="stokr_platform",
                        user="postgres", password=DB_PW)
cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

TRADE_CAPITAL = Decimal("5000")

# Only analyze runs from the fresh-logic deploy (after the 2026-06-14 08:38 IST
# restart). Pass a different cutoff as argv[1] if needed.
import sys as _sys
CUTOFF = _sys.argv[1] if len(_sys.argv) > 1 else "2026-06-14 03:08:00+00"

# 1-WEEK = Jun 6→12; 1-MONTH = May 19→Jun 12
PERIODS = {
    "1_WEEK":  ("2026-06-06 04:00:00+00", "2026-06-12 10:00:00+00"),
    "1_MONTH": ("2026-05-19 04:00:00+00", "2026-06-12 10:00:00+00"),
}

STRATEGIES = ["ADV_CASH", "NIFTY_CATCHUP", "VWAP_BOUNCE", "VWAP_SQUEEZE", "VWAP_CLOSE_RECLAIM"]

print("=" * 80)
print("BACKTEST ANALYSIS  |  ₹5,000/trade  |  ₹75k capital  |  15 max positions")
print("Outcome simulation: scan 1m bars from signal bar; TP or SL hit wins/loses.")
print("=" * 80)

for period_name, (range_start, range_end) in PERIODS.items():
    print(f"\n{'='*60}")
    print(f"PERIOD: {period_name}   {range_start[:10]} → {range_end[:10]}")
    print(f"{'='*60}")

    period_pnl = Decimal("0")
    period_wins = period_losses = period_expired = period_total = 0

    for strategy in STRATEGIES:
        # Get the LATEST completed run per symbol (deduplicate), fresh logic only
        cur.execute("""
            SELECT DISTINCT ON (symbol) id AS run_id, symbol
            FROM backtest_runs
            WHERE strategy_key = %s AND status = 'COMPLETED'
              AND range_start = %s AND range_end = %s
              AND created_at >= %s
            ORDER BY symbol, created_at DESC
        """, (strategy, range_start, range_end, CUTOFF))
        runs = cur.fetchall()

        if not runs:
            print(f"\n  {strategy:20s}: NO COMPLETED RUNS")
            continue

        run_ids = [str(r["run_id"]) for r in runs]
        placeholders = ",".join(["%s"] * len(run_ids))

        cur.execute(f"""
            SELECT ss.symbol, ss.signal_type, ss.candle_timestamp,
                   ss.entry_reference_price, ss.target_price, ss.stop_price
            FROM strategy_signals ss
            WHERE ss.backtest_run_id::text IN ({placeholders})
              AND ss.entry_reference_price IS NOT NULL
              AND ss.target_price IS NOT NULL
              AND ss.stop_price IS NOT NULL
              AND ss.entry_reference_price > 0
            ORDER BY ss.symbol, ss.candle_timestamp
        """, run_ids)
        signals = cur.fetchall()

        if not signals:
            print(f"\n  {strategy:20s}: {len(runs)} runs, 0 signals")
            continue

        sigs_by_symbol = defaultdict(list)
        for sig in signals:
            sigs_by_symbol[sig["symbol"]].append(sig)

        strat_pnl = Decimal("0")
        wins = losses = expired = skipped = 0

        for symbol, sym_sigs in sigs_by_symbol.items():
            cur.execute("""
                SELECT open_time, high_price, low_price, close_price
                FROM marketdata_candles
                WHERE symbol = %s AND timeframe = '1m'
                  AND open_time >= %s AND open_time <= %s
                ORDER BY open_time ASC
            """, (symbol, range_start, range_end))
            candles = cur.fetchall()
            candle_list = [(c["open_time"], Decimal(str(c["high_price"])),
                            Decimal(str(c["low_price"])), Decimal(str(c["close_price"])))
                           for c in candles]
            # Build index: open_time → position in candle_list for fast lookup
            time_index = {ot: i for i, (ot, *_) in enumerate(candle_list)}

            for sig in sym_sigs:
                entry_time = sig["candle_timestamp"]
                entry  = Decimal(str(sig["entry_reference_price"]))
                target = Decimal(str(sig["target_price"]))
                stop   = Decimal(str(sig["stop_price"]))

                qty = int(TRADE_CAPITAL / entry)
                if qty == 0:
                    skipped += 1
                    continue

                # Direction: target above entry → LONG, else SHORT
                is_long = target >= entry

                # Find start index: first bar AFTER the signal bar
                start_idx = time_index.get(entry_time)
                if start_idx is None:
                    skipped += 1
                    continue
                start_idx += 1  # start scanning the NEXT bar

                hit_target = hit_stop = False
                exit_price = None

                for i in range(start_idx, len(candle_list)):
                    ot, high, low, close = candle_list[i]

                    if is_long:
                        # Check SL first (conservative: assume worst case within bar)
                        if low <= stop:
                            hit_stop = True
                            exit_price = stop
                            break
                        if high >= target:
                            hit_target = True
                            exit_price = target
                            break
                    else:  # SHORT
                        if high >= stop:
                            hit_stop = True
                            exit_price = stop
                            break
                        if low <= target:
                            hit_target = True
                            exit_price = target
                            break

                    # End-of-session close: 10:00 UTC = 3:30 PM IST market close
                    if ot.hour == 10 and ot.minute == 0:
                        exit_price = close
                        break

                if exit_price is None:
                    expired += 1
                    continue

                if is_long:
                    pnl = (exit_price - entry) * qty
                else:
                    pnl = (entry - exit_price) * qty

                if hit_target:
                    wins += 1
                elif hit_stop:
                    losses += 1
                else:
                    expired += 1

                strat_pnl += pnl

        total_signals = len(signals)
        resolved = wins + losses + expired
        wr = (wins / resolved * 100) if resolved > 0 else 0.0

        period_pnl += strat_pnl
        period_total += total_signals
        period_wins += wins
        period_losses += losses
        period_expired += expired

        syms_with_data = len(sigs_by_symbol)
        print(f"\n  {strategy:20s}: {total_signals:>5} signals  ({len(runs)} runs / {syms_with_data} symbols)")
        print(f"    Target: {wins:>4} | Stop: {losses:>4} | Expired: {expired:>4} | Skipped: {skipped}")
        print(f"    Win rate: {wr:5.1f}%   Net P&L: ₹{strat_pnl:>10,.0f}")

    all_resolved = period_wins + period_losses + period_expired
    overall_wr = (period_wins / all_resolved * 100) if all_resolved > 0 else 0
    print(f"\n  {'─'*56}")
    print(f"  {period_name} TOTAL: {period_total} signals")
    print(f"  Wins: {period_wins} | Losses: {period_losses} | Expired: {period_expired} | Win rate: {overall_wr:.1f}%")
    print(f"  *** NET P&L ({period_name}): ₹{period_pnl:,.0f} ***")

cur.close()
conn.close()
print("\nDone.")
