"""COMPREHENSIVE PORTFOLIO BACKTEST - All Strategies, Real Data
Runs on server against stokr_lite PostgreSQL. Single source of truth.
"""
import psycopg2, datetime, collections, statistics

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

# ━━━ CONFIG ━━━
CAPITAL = 25000
BROKERAGE = 40  # ₹20 buy + ₹20 sell
THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)

# ━━━ DATA LOAD ━━━
cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily'")
daily_symbols = [r[0] for r in cur.fetchall()]
print(f"Daily symbols: {len(daily_symbols)}")

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min'")
min_symbols = [r[0] for r in cur.fetchall()]
print(f"1-min symbols: {len(min_symbols)}")

Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type strat')

# ═══════════════════════════════════════════
# STRATEGY 1: BTST — EOD Momentum + Next Day Gap
# ═══════════════════════════════════════════
btst_trades = []

for sym in daily_symbols:
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 25: continue

    for i in range(24, len(rows) - 1):
        # EOD conditions on day[i]
        today = rows[i]
        tomorrow = rows[i + 1]

        o, h, l, c, v = today[1], today[2], today[3], today[4], today[5]
        if o <= 0: continue

        # BTST signal: close near high, volume surge, range 1.5-7%, above 20 SMA
        range_pct = (h - l) / o * 100
        if range_pct < 1.5 or range_pct > 7: continue

        close_near_high = (h - c) / o * 100
        if close_near_high > 0.5: continue  # Close must be within 0.5% of high

        sma20 = sum(rows[j][4] for j in range(i - 19, i + 1)) / 20
        if c <= sma20: continue

        avg_vol_20 = sum(rows[j][5] for j in range(i - 19, i + 1)) / 20
        if avg_vol_20 <= 0: continue
        vol_ratio = v / avg_vol_20
        if vol_ratio < 1.5: continue

        # Entry: EOD close today | Exit: tomorrow action
        entry = c
        next_open = tomorrow[1]
        next_high = tomorrow[2]
        next_low = tomorrow[3]
        next_close = tomorrow[4]

        # Gap-up open = instant profit
        gap_pnl = (next_open - entry) / entry * CAPITAL

        # Target 3%, SL 1.5% from entry
        target = entry * 1.03
        sl = entry * 0.985

        # Simulate: did we hit target or SL intraday?
        if next_high >= target:
            exit_px = target
            ext = 'TARGET'
        elif next_low <= sl:
            exit_px = sl
            ext = 'SL'
        else:
            exit_px = next_close
            ext = 'EOD'

        net = CAPITAL * (exit_px - entry) / entry - BROKERAGE
        btst_trades.append(Trade(sym, entry, exit_px, net, today[0], ext, 'BTST'))

    if len(daily_symbols[:10]) > 0 and sym == daily_symbols[5]:
        pass  # progress would be here in real run


# ═══════════════════════════════════════════
# STRATEGY 2: 20-Day Breakout (Darvas Box)
# ═══════════════════════════════════════════
breakout_trades = []

for sym in daily_symbols:
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 25: continue

    for i in range(24, len(rows) - 5):
        today = rows[i]
        c, v = today[4], today[5]

        # 20-day high breakout
        high_20 = max(rows[j][2] for j in range(i - 19, i + 1))
        if c <= high_20 * 0.98: continue  # Must be within 2% of 20-day high

        # Volume surge
        avg_vol = sum(rows[j][5] for j in range(i - 19, i + 1)) / 20
        if avg_vol <= 0 or v < avg_vol * 1.3: continue

        # Above 50 SMA
        if i >= 50:
            sma50 = sum(rows[j][4] for j in range(i - 49, i + 1)) / 50
            if c <= sma50: continue

        entry = c
        target = entry * 1.08
        sl = entry * 0.95

        # Hold up to 5 days
        exit_px = entry
        ext = 'TIME'
        for d in range(1, min(6, len(rows) - i)):
            nd = rows[i + d]
            if nd[2] >= target:
                exit_px = target; ext = 'TARGET'; break
            if nd[3] <= sl:
                exit_px = sl; ext = 'SL'; break
            exit_px = nd[4]
            ext = 'EOD'

        net = CAPITAL * (exit_px - entry) / entry - BROKERAGE
        breakout_trades.append(Trade(sym, entry, exit_px, net, today[0], ext, '20D_BREAK'))


# ═══════════════════════════════════════════
# STRATEGY 3: 3-Day Swing (Momentum Pullback)
# ═══════════════════════════════════════════
swing_trades = []

for sym in daily_symbols:
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 30: continue

    for i in range(29, len(rows) - 3):
        today = rows[i]

        # Look for pullback: 3 red days then green day
        if i < 4: continue
        d1, d2, d3, d4 = rows[i - 3], rows[i - 2], rows[i - 1], rows[i]

        reds = sum(1 for d in [d1, d2, d3] if d[4] < d[1])
        green_today = d4[4] > d4[1]

        if reds < 2 or not green_today: continue

        # Above 200 SMA
        if i >= 200:
            sma200 = sum(rows[j][4] for j in range(i - 199, i + 1)) / 200
            if d4[4] <= sma200: continue

        # Volume on green day > avg
        avg_v = sum(rows[j][5] for j in range(i - 19, i + 1)) / 20
        if avg_v <= 0 or d4[5] < avg_v * 1.2: continue

        entry = d4[4]
        target = entry * 1.05
        sl = entry * 0.97

        exit_px = entry
        ext = 'TIME'
        for d in range(1, min(4, len(rows) - i)):
            nd = rows[i + d]
            if nd[2] >= target:
                exit_px = target; ext = 'TARGET'; break
            if nd[3] <= sl:
                exit_px = sl; ext = 'SL'; break
            exit_px = nd[4]; ext = 'EOD'

        net = CAPITAL * (exit_px - entry) / entry - BROKERAGE
        swing_trades.append(Trade(sym, entry, exit_px, net, today[0], ext, '3D_SWING'))


conn.close()

# ═══════════════════════════════════════════
# RESULTS TABLE
# ═══════════════════════════════════════════
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

results = [
    analyze("BTST (EOD to Next Day)", btst_trades),
    analyze("20D Breakout (Darvas)", breakout_trades),
    analyze("3D Swing (Pullback)", swing_trades),
]

print(f"\n{'='*85}")
print(f"COMPREHENSIVE BACKTEST — {len(daily_symbols)} symbols, 3 months, Rs.{CAPITAL:,}/trade")
print(f"{'='*85}")
print(f"{'Strategy':<28} {'Trades':>7} {'WR':>6} {'Net P&L':>10} {'/Mo':>8} {'PF':>5} {'AvgW':>7} {'AvgL':>7}")
print(f"{'-'*85}")

for name, n, wr, pnl, mo, pf, aw, al in results:
    if n == 0:
        print(f"{name:<28} {'0':>7} {'--':>6} {'--':>10} {'--':>8}")
    else:
        print(f"{name:<28} {n:>7} {wr:>5.0f}% {pnl:>+9,.0f} {mo:>+7,.0f} {pf:>4.1f}x {aw:>+6,.0f} {al:>+6,.0f}")

# Combined
all_trades = btst_trades + breakout_trades + swing_trades
comb = analyze("COMBINED PORTFOLIO", all_trades)
print(f"{'-'*85}")
print(f"{comb[0]:<28} {comb[1]:>7} {comb[2]:>5.0f}% {comb[3]:>+9,.0f} {comb[4]:>+7,.0f} {comb[5]:>4.1f}x {comb[6]:>+6,.0f} {comb[7]:>+6,.0f}")

# Exit analysis per strategy
for label, trades in [("BTST", btst_trades), ("20DBRK", breakout_trades), ("3DSWING", swing_trades)]:
    if trades:
        ec = collections.Counter(t.exit_type for t in trades)
        print(f"\n{label} exits: {dict(ec)}")

# Capital requirement
max_positions = 5  # 5 concurrent positions
total_capital = CAPITAL * max_positions
if all_trades:
    roi = comb[3] * 0.75 / total_capital * 100 / 3  # 75% user share
    print(f"\nTotal Capital (5 pos): Rs.{total_capital:,}")
    print(f"User Profit (75%):     Rs.{comb[3]*0.75:,.0f} = Rs.{comb[4]*0.75:,.0f}/mo")
    print(f"Monthly ROI:           {roi:.1f}%")

# ━━━ INTRA-DAY SUMMARY (from previous runs) ━━━
print(f"\n{'='*85}")
print(f"PREVIOUS INTRADAY RESULTS (1-min candles, same 3-month period)")
print(f"{'='*85}")
print(f"{'QuickFlip v3':<28} {'77':>7} {'36%':>6} {'-Rs.2,136':>10}")
print(f"{'Momentum Surge':<28} {'65':>7} {'~35%':>6} {'-Rs.1,559':>10}")
print(f"{'Inst Footprint':<28} {'3,809':>7} {'17%':>6} {'-Rs.168,902':>10}")
print(f"{'Inverse Signals':<28} {'28,412':>7} {'23%':>6} {'-Rs.1,055,124':>10}")
print(f"  ALL intraday strategies lose money. Rs.40 brokerage is the bottleneck.")
print(f"  Daily-candle swing strategies are the only viable path.")
