"""ORB + Existing strategies backtest on 1-min data."""
import psycopg2, datetime, collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min'")
symbols = [r[0] for r in cur.fetchall()]

THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)
CAPITAL = 25000
BROKER = 40
Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type strat')

orb_trades = []
vreversal_trades = []
oversold_trades = []

for si, sym in enumerate(symbols):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='1min' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 300: continue

    by_date = {}
    for r in rows:
        by_date.setdefault(r[0].date(), []).append(r)

    for date, candles in by_date.items():
        n = len(candles)
        if n < 30: continue

        # ORB: first 15 candles = opening range (9:15-9:29)
        orb_candles = candles[:15]
        orb_high = max(c[2] for c in orb_candles)
        orb_low = min(c[3] for c in orb_candles)
        orb_range = orb_high - orb_low
        if orb_range <= 0: continue

        # ━━━ STRATEGY: ORB Breakout ━━━
        for i in range(15, min(n - 5, 180)):  # up to first 180 min (12:15 PM)
            c = candles[i]
            t = c[0]
            total_min = t.hour * 60 + t.minute
            if total_min > 14 * 60 + 30: continue
            if 11 * 60 + 30 <= total_min < 13 * 60: continue

            entry = None
            direction = None

            if c[4] > orb_high:  # Close breaks ORB high
                entry = orb_high
                direction = 'LONG'
            elif c[4] < orb_low:  # Close breaks ORB low
                entry = orb_low
                direction = 'SHORT'

            if entry is None or entry <= 0: continue

            if direction == 'LONG':
                target = entry + orb_range * 1.5
                sl = entry - orb_range * 0.5
                exit_px = entry
                ext = 'TIME'
                for j in range(i + 1, min(i + 60, n)):
                    nc = candles[j]
                    if nc[2] >= target:
                        exit_px = target; ext = 'TARGET'; break
                    if nc[3] <= sl:
                        exit_px = sl; ext = 'SL'; break
                    exit_px = nc[4]
                pnl = CAPITAL * (exit_px - entry) / entry - BROKER
            else:
                target = entry - orb_range * 1.5
                sl = entry + orb_range * 0.5
                exit_px = entry
                ext = 'TIME'
                for j in range(i + 1, min(i + 60, n)):
                    nc = candles[j]
                    if nc[3] <= target:
                        exit_px = target; ext = 'TARGET'; break
                    if nc[2] >= sl:
                        exit_px = sl; ext = 'SL'; break
                    exit_px = nc[4]
                pnl = CAPITAL * (entry - exit_px) / entry - BROKER

            orb_trades.append(Trade(sym, entry, exit_px, pnl, c[0], ext, 'ORB'))
            break  # One entry per day

        # ━━━ STRATEGY: Oversold Bounce ━━━
        # After 30+ candles, if RSI < 30 and candle near day low with vol spike, buy bounce
        for i in range(30, min(n - 5, 200)):
            c = candles[i]
            entry_px = c[4]
            if entry_px < 50: continue
            t = c[0]
            total_min = t.hour * 60 + t.minute
            if total_min > 14 * 60 + 30: continue
            if 11 * 60 + 30 <= total_min < 13 * 60: continue

            # RSI14
            window = candles[max(0, i - 13):i + 1]
            gains = losses = 0.0
            for j in range(1, len(window)):
                ch = window[j][4] - window[j - 1][4]
                if ch > 0: gains += ch
                else: losses += abs(ch)
            rsi = 100 - (100 / (1 + (gains / max(1, losses) / 14)))
            if rsi >= 30: continue

            # Near day low
            day_low = min(candles[j][3] for j in range(i))
            if day_low <= 0: continue
            dist_low = (entry_px - day_low) / day_low * 100
            if dist_low > 1.5: continue

            # Volume spike
            avg_vol = sum(candles[j][5] for j in range(max(0, i - 20), i)) / 20
            if avg_vol <= 0: continue
            if c[5] < avg_vol * 1.5: continue

            # Green candle
            if c[4] <= c[1]: continue

            target = entry_px * 1.02
            sl = day_low * 0.995
            exit_px = entry_px
            ext = 'TIME'
            for j in range(i + 1, min(i + 30, n)):
                nc = candles[j]
                if nc[2] >= target:
                    exit_px = target; ext = 'TARGET'; break
                if nc[3] <= sl:
                    exit_px = sl; ext = 'SL'; break
                exit_px = nc[4]

            pnl = CAPITAL * (exit_px - entry_px) / entry_px - BROKER
            oversold_trades.append(Trade(sym, entry_px, exit_px, pnl, c[0], ext, 'OVERSOLD'))
            break

        # ━━━ STRATEGY: Micro V-Reversal ━━━
        # Look for sharp dip + V-shaped recovery
        for i in range(20, min(n - 5, 200)):
            c = candles[i]
            entry_px = c[4]
            if entry_px < 50: continue

            t = c[0]
            total_min = t.hour * 60 + t.minute
            if total_min > 14 * 60 + 30: continue
            if 11 * 60 + 30 <= total_min < 13 * 60: continue

            # Last 5 candles: sharp dip then recovery
            if i < 5: continue
            c_5 = candles[i - 5]
            c_3 = candles[i - 3]
            c_1 = candles[i - 1]

            # Dip: 5-candle low to 3-candle low to 1-candle low forms a V bottom
            dip_5_to_3 = (c_3[3] - c_5[3]) / c_5[3] * 100  # should be negative (fell)
            recover_3_to_1 = (c_1[4] - c_3[3]) / c_3[3] * 100  # should be positive (bounced)

            if dip_5_to_3 > -0.5: continue  # Not enough dip
            if recover_3_to_1 < 0.3: continue  # Not enough recovery

            # Current candle must be green
            if c[4] <= c[1]: continue

            # Volume on recovery > dip volume
            vol_recovery = sum(candles[j][5] for j in range(i - 2, i + 1)) / 3
            vol_dip = sum(candles[j][5] for j in range(i - 5, i - 2)) / 3
            if vol_dip <= 0 or vol_recovery < vol_dip * 1.3: continue

            target = entry_px * 1.015
            sl = c_3[3] * 0.998  # Below V-bottom

            exit_px = entry_px
            ext = 'TIME'
            for j in range(i + 1, min(i + 20, n)):
                nc = candles[j]
                if nc[2] >= target:
                    exit_px = target; ext = 'TARGET'; break
                if nc[3] <= sl:
                    exit_px = sl; ext = 'SL'; break
                exit_px = nc[4]

            pnl = CAPITAL * (exit_px - entry_px) / entry_px - BROKER
            vreversal_trades.append(Trade(sym, entry_px, exit_px, pnl, c[0], ext, 'V_REV'))
            break

    if (si + 1) % 100 == 0:
        print(f"  {si+1}/{len(symbols)} | ORB={len(orb_trades)} OS={len(oversold_trades)} VR={len(vreversal_trades)}")

conn.close()

def analyze(name, trades):
    if not trades: return (name, 0, 0, 0, 0, 0, 0, 0)
    wins = [t for t in trades if t.pnl > 0]
    losses = [t for t in trades if t.pnl <= 0]
    total = sum(t.pnl for t in trades)
    wr = len(wins) / len(trades) * 100
    pf = sum(t.pnl for t in wins) / abs(sum(t.pnl for t in losses)) if losses else 999
    avg_win = sum(t.pnl for t in wins) / max(1, len(wins))
    avg_loss = sum(t.pnl for t in losses) / max(1, len(losses))
    monthly = total / 3
    return (name, len(trades), wr, total, monthly, pf, avg_win, avg_loss)

results = [analyze("ORB Breakout (15min)", orb_trades),
           analyze("Oversold Bounce (RSI)", oversold_trades),
           analyze("Micro V-Reversal", vreversal_trades)]

print(f"\n{'='*85}")
print(f"INTRADAY STRATEGIES — 1-min candles, {len(symbols)} symbols, 3 months, Rs.{CAPITAL:,}/trade")
print(f"{'='*85}")
print(f"{'Strategy':<30} {'Trades':>7} {'WR':>6} {'Net P&L':>10} {'/Mo':>8} {'PF':>5} {'AvgW':>7} {'AvgL':>7}")
print(f"{'-'*85}")
for name, n, wr, pnl, mo, pf, aw, al in results:
    if n == 0:
        print(f"{name:<30} {'0':>7}")
    else:
        print(f"{name:<30} {n:>7} {wr:>5.0f}% {pnl:>+9,.0f} {mo:>+7,.0f} {pf:>4.1f}x {aw:>+6,.0f} {al:>+6,.0f}")

# Exit analysis for each
for label, trades in [("ORB", orb_trades), ("OVERSOLD", oversold_trades), ("VREVERSAL", vreversal_trades)]:
    if trades:
        wins = [t for t in trades if t.pnl > 0]
        ec = collections.Counter(t.exit_type for t in trades)
        print(f"\n{label}: {len(trades)}t, {len(wins)}W, exits={dict(ec)}")

# ━━━ FINAL COMPARISON ━━━
print(f"\n{'='*85}")
print(f"FINAL SCOREBOARD")
print(f"{'='*85}")
all_data = [
    ("3D Swing (Daily)", 176, 52.0, +8303),
    ("ORB Breakout", len(orb_trades), results[0][2] if orb_trades else 0, results[0][3] if orb_trades else 0),
    ("Oversold Bounce", len(oversold_trades), results[1][2] if oversold_trades else 0, results[1][3] if oversold_trades else 0),
    ("Micro V-Reversal", len(vreversal_trades), results[2][2] if vreversal_trades else 0, results[2][3] if vreversal_trades else 0),
    ("BTST (Daily)", 53, 38.0, -913),
    ("20D Breakout", 160, 48.0, -10837),
    ("Inst Footprint", 3809, 17.0, -168902),
    ("QuickFlip v3", 77, 36.0, -2136),
]

for name, n, wr, pnl in sorted(all_data, key=lambda x: -x[3]):
    status = "PASS" if pnl > 0 else "FAIL"
    print(f"  {status:>4} | {name:<24} {n:>6}t {wr:>5.0f}% {pnl:>+10,.0f}")
