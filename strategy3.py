"""3 CONTRARIAN STRATEGIES - No RSI, No MA Cross, No Pattern Mumbo Jumbo."""
import psycopg2, datetime, collections, statistics, math

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily'")
all_symbols = [r[0] for r in cur.fetchall()]

THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)
CAP = 25000; BR = 40

friday_trades = []
meanrev_trades = []
momcont_trades = []

def dd(d): return d.strftime("%a")

for si, sym in enumerate(sorted(all_symbols)):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 30: continue

    # ═══════════ STRATEGY 1: FRIDAY-TO-MONDAY ═══════════
    for i in range(1, len(rows) - 1):
        today = rows[i]; tomorrow = rows[i + 1]
        if dd(today[0]) != 'Fri': continue

        entry = today[4]  # Friday close
        exit_px = tomorrow[1]  # Monday open
        if entry <= 0: continue

        pct = (exit_px - entry) / entry * 100
        # Only if Friday was a red day (dip buy)
        if today[4] >= today[1]: continue  # Only take red Fridays

        net = CAP * (exit_px - entry) / entry - BR
        friday_trades.append({'sym': sym, 'entry': entry, 'exit': exit_px,
            'pnl': net, 'dt': today[0], 'pct': pct})

    # ═══════════ STRATEGY 2: 2-SIGMA MEAN REVERSION ═══════════
    for i in range(25, len(rows) - 5):
        today = rows[i]; c = today[4]
        if c <= 0: continue

        # 20-day mean + std
        closes = [rows[j][4] for j in range(i - 20, i)]
        mean = statistics.mean(closes)
        stdev = statistics.stdev(closes)
        if stdev <= 0: continue

        z = (c - mean) / stdev  # Z-score

        # ENTRY: >=2 sigma below mean (extreme undervaluation)
        if z > -2.0: continue

        # Green candle today
        if c <= today[1]: continue

        # Above 200 SMA
        if i >= 200:
            sma200 = sum(rows[j][4] for j in range(i - 199, i + 1)) / 200
            if c <= sma200: continue

        entry = c
        target = mean  # Target = mean reversion
        sl = c * 0.97

        exit_px = entry; ext = 'TIME'
        for d in range(1, min(8, len(rows) - i)):
            nd = rows[i + d]
            if nd[2] >= target:
                exit_px = target; ext = 'MEAN'; break
            if nd[3] <= sl:
                exit_px = sl; ext = 'SL'; break
            exit_px = nd[4]; ext = 'EOD'

        net = CAP * (exit_px - entry) / entry - BR
        meanrev_trades.append({'sym': sym, 'entry': entry, 'exit': exit_px,
            'pnl': net, 'dt': today[0], 'ext': ext, 'z': z})

    # ═══════════ STRATEGY 3: 3-DAY MOMENTUM CONTINUATION ═══════════
    for i in range(24, len(rows) - 4):
        d3, d2, d1, today = rows[i - 3], rows[i - 2], rows[i - 1], rows[i]

        # 3 consecutive green days
        if not (d3[4] > d3[1] and d2[4] > d2[1] and d1[4] > d1[1] and today[4] > today[1]):
            continue

        # Increasing volume
        if not (d3[5] < d2[5] < d1[5] < today[5]):
            continue

        # Accumulated gain over 3 days > 3%
        gain = (today[4] - d3[1]) / d3[1] * 100
        if gain < 3 or gain > 12: continue  # Not too extended

        c = today[4]
        entry = c
        target = c * 1.06  # 6% further
        sl = c * 0.96      # 4% stop

        exit_px = entry; ext = 'TIME'
        for d in range(1, min(6, len(rows) - i)):
            nd = rows[i + d]
            if nd[2] >= target:
                exit_px = target; ext = 'TARGET'; break
            if nd[3] <= sl:
                exit_px = sl; ext = 'SL'; break
            exit_px = nd[4]; ext = 'EOD'

        net = CAP * (exit_px - entry) / entry - BR
        momcont_trades.append({'sym': sym, 'entry': entry, 'exit': exit_px,
            'pnl': net, 'dt': today[0], 'ext': ext, 'gain': gain})

    if (si + 1) % 30 == 0:
        print(f"  {si+1}/{len(all_symbols)} | FRI={len(friday_trades)} SIGMA={len(meanrev_trades)} MOM={len(momcont_trades)}")

conn.close()

# ═══════════ RESULTS ═══════════
def report(name, trades):
    if not trades: return f"{name}: NO TRADES"
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    n = len(trades); w = len(wins); l = len(losses)
    total = sum(t['pnl'] for t in trades)
    wr = w / n * 100
    aw = sum(t['pnl'] for t in wins) / max(1, w)
    al = sum(t['pnl'] for t in losses) / max(1, l)
    pf = sum(t['pnl'] for t in wins) / abs(sum(t['pnl'] for t in losses)) if l else 999

    # Drawdown
    peak = dd = 0.0; cum = 0
    for t in trades:
        cum += t['pnl']
        if cum > peak: peak = cum
        dd = max(dd, peak - cum)

    # Best/worst trade
    best_t = max(trades, key=lambda t: t['pnl'])
    worst_t = min(trades, key=lambda t: t['pnl'])

    # Monthly
    monthly = collections.defaultdict(float)
    for t in trades:
        m = t['dt'].strftime('%Y-%m')
        monthly[m] += t['pnl']

    lines = [
        f"\n{'='*90}",
        f"{name}",
        f"{'='*90}",
        f"Trades: {n} | Wins: {w} | Losses: {l} | WR: {wr:.1f}%",
        f"Net P&L: Rs.{total:,.0f} | Monthly: Rs.{total/3:,.0f}",
        f"Avg Win: Rs.{aw:,.0f} | Avg Loss: Rs.{al:,.0f} | PF: {pf:.2f}",
        f"Max DD: Rs.{dd:,.0f}",
        f"Best: {best_t['sym']} Rs.{best_t['pnl']:,.0f} | Worst: {worst_t['sym']} Rs.{worst_t['pnl']:,.0f}",
    ]
    for m, p in sorted(monthly.items()):
        lines.append(f"  {m}: Rs.{p:,.0f}")

    # First 10 trades for detail
    lines.append(f"\nFirst 10 trades:")
    lines.append(f"{'Date':>12} {'Sym':<12} {'Entry':>8} {'Exit':>8} {'P&L':>9}")
    for t in trades[:10]:
        lines.append(f"{t['dt'].strftime('%d-%b-%Y'):>12} {t['sym']:<12} {t['entry']:>8.1f} {t['exit']:>8.1f} {t['pnl']:>+9.0f}")

    return '\n'.join(lines)

print(report("STRATEGY 1: FRIDAY RED → MONDAY GAP", friday_trades))
print(report("STRATEGY 2: 2-SIGMA MEAN REVERSION", meanrev_trades))
print(report("STRATEGY 3: 3-DAY VOLUME MOMENTUM", momcont_trades))

# Combined
all_t = friday_trades + meanrev_trades + momcont_trades
print(report("ALL 3 COMBINED", all_t))

# ═══════════ FINAL SCOREBOARD ═══════════
all_results = [
    ("3D Swing (Daily)", 176, 52.0, 8303),
    ("Friday Gap (this)", len(friday_trades),
        len([t for t in friday_trades if t['pnl']>0])/max(1,len(friday_trades))*100 if friday_trades else 0,
        sum(t['pnl'] for t in friday_trades) if friday_trades else 0),
    ("2-Sigma Reversion", len(meanrev_trades),
        len([t for t in meanrev_trades if t['pnl']>0])/max(1,len(meanrev_trades))*100 if meanrev_trades else 0,
        sum(t['pnl'] for t in meanrev_trades) if meanrev_trades else 0),
    ("3D Momentum", len(momcont_trades),
        len([t for t in momcont_trades if t['pnl']>0])/max(1,len(momcont_trades))*100 if momcont_trades else 0,
        sum(t['pnl'] for t in momcont_trades) if momcont_trades else 0),
]

print(f"\n{'='*90}")
print(f"FINAL SCOREBOARD — All Strategies")
print(f"{'='*90}")
for name, n, wr, pnl in sorted(all_results, key=lambda x: -x[3]):
    status = "PROFIT" if pnl > 0 else "LOSS"
    print(f"  {status:>6} | {name:<25} {n:>5}t  {wr:>5.0f}%  Rs.{pnl:>+10,.0f}")
