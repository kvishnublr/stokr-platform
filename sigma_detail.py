"""2-SIGMA MEAN REVERSION — Detailed report with all trades."""
import psycopg2, datetime, collections, statistics

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily'")
all_symbols = [r[0] for r in cur.fetchall()]

THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)
CAP = 25000; BR = 40
trades = []

for sym in sorted(all_symbols):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 30: continue

    for i in range(25, len(rows) - 5):
        today = rows[i]; c, v, dt = today[4], today[5], today[0]
        if c <= 0: continue

        # 20-day stats
        closes = [rows[j][4] for j in range(i - 20, i)]
        mean = statistics.mean(closes)
        stdev = statistics.stdev(closes)
        if stdev <= 0: continue
        z = (c - mean) / stdev

        if z > -1.5: continue  # Loosen to -1.5 sigma (more trades)
        if c <= today[1]: continue  # Green candle

        if i >= 200:
            sma200 = sum(rows[j][4] for j in range(i - 199, i + 1)) / 200
            if c <= sma200: continue

        # Volume filter
        avg_vol = sum(rows[j][5] for j in range(i - 20, i)) / 20
        if avg_vol <= 0 or v < avg_vol: continue

        entry = c
        target = mean  # Mean reversion target
        sl = c * 0.97

        # Test 3 different exit approaches
        # Approach: simple EOD exit after N days
        for max_hold in [3, 5, 7, 10]:
            exit_px = entry; exit_dt = dt; ext = 'TIME'
            for d in range(1, min(max_hold + 1, len(rows) - i)):
                nd = rows[i + d]
                if nd[2] >= target:
                    exit_px = target; exit_dt = nd[0]; ext = f'TGT_{d}d'; break
                if nd[3] <= sl:
                    exit_px = sl; exit_dt = nd[0]; ext = f'SL_{d}d'; break
                exit_px = nd[4]; exit_dt = nd[0]; ext = f'EOD_{d}d'

            net = CAP * (exit_px - entry) / entry - BR
            day_ct = (exit_dt - dt).days
            pnl_pct = (exit_px - entry) / entry * 100

            trades.append({
                'sym': sym, 'entry_dt': dt, 'entry': entry,
                'exit_dt': exit_dt, 'exit': exit_px,
                'pnl': net, 'z': z, 'hold_lim': max_hold,
                'ext': ext, 'days': day_ct, 'pnl_pct': pnl_pct
            })

conn.close()

# Best hold period
for hold in [3, 5, 7, 10]:
    filtered = [t for t in trades if t['hold_lim'] == hold]
    if not filtered: continue
    wins = [t for t in filtered if t['pnl'] > 0]
    n = len(filtered); w = len(wins)
    total = sum(t['pnl'] for t in filtered)
    wr = w/n*100
    print(f"Hold {hold}d: {n}t {wr:.0f}% WR Rs.{total:,.0f} avg/pnl Rs.{total/max(1,n):.0f}")

# Best Z-score threshold
for z_thresh in [-2.5, -2.0, -1.5]:
    filtered = [t for t in trades if t['z'] <= z_thresh and t['hold_lim'] == 7]
    if not filtered: continue
    wins = [t for t in filtered if t['pnl'] > 0]
    n = len(filtered); w = len(wins)
    total = sum(t['pnl'] for t in filtered)
    wr = w/n*100
    print(f"Z<{z_thresh} (7d hold): {n}t {wr:.0f}% WR Rs.{total:,.0f}")

# Detailed report for Z<-1.5, 7d hold
best_trades = [t for t in trades if t['z'] <= -1.5 and t['hold_lim'] == 7]
if best_trades:
    print(f"\n{'='*120}")
    print(f"2-SIGMA MEAN REVERSION — Z<-1.5, 7d Hold — Trade-by-Trade")
    print(f"{'='*120}")
    print(f"{'#':>3} {'Stock':<15} {'EntryDate':>12} {'Entry':>8} {'ExitDate':>12} {'Exit':>8} {'Z':>6} {'Days':>5} {'Type':>10} {'PnL:%':>7} {'P&L':>9}")
    print(f"{'-'*120}")
    cum = 0
    for idx, t in enumerate(sorted(best_trades, key=lambda x: x['entry_dt'])):
        cum += t['pnl']
        print(f"{idx+1:>3} {t['sym']:<15} {t['entry_dt'].strftime('%d-%b-%Y'):>12} {t['entry']:>8.1f} {t['exit_dt'].strftime('%d-%b-%Y'):>12} {t['exit']:>8.1f} {t['z']:>+5.1f} {t['days']:>5}d {t['ext']:>10} {t['pnl_pct']:>+6.2f}% {t['pnl']:>+7.0f}")

    wins = [t for t in best_trades if t['pnl'] > 0]
    losses = [t for t in best_trades if t['pnl'] <= 0]
    total = sum(t['pnl'] for t in best_trades)
    print(f"\nSummary: {len(best_trades)}t WR={len(wins)/len(best_trades)*100:.0f}% P&L=Rs.{total:,.0f} PF={sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)):.2f}" if losses else "")
    print(f"Avg Win: Rs.{sum(t['pnl'] for t in wins)/max(1,len(wins)):.0f} | Avg Loss: Rs.{sum(t['pnl'] for t in losses)/max(1,len(losses)):.0f}")
