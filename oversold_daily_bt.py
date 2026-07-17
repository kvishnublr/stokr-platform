"""Positional Oversold Bounce — Daily candles, RSI<30, hold 3-10 days."""
import psycopg2, datetime, collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily'")
symbols = [r[0] for r in cur.fetchall()]
THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)
CAPITAL = 25000
BROKER = 40
Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type strat')

oversold_trades = []

for sym in symbols:
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 30: continue

    for i in range(29, len(rows) - 3):
        today = rows[i]
        c, v = today[4], today[5]

        # RSI14 on daily candles
        window = rows[i - 13:i + 1]
        gains = losses = 0.0
        for j in range(1, len(window)):
            ch = window[j][4] - window[j - 1][4]
            if ch > 0: gains += ch
            else: losses += abs(ch)
        rsi = 100 - (100 / (1 + (gains / max(0.0001, losses))))

        if rsi >= 30: continue

        # Green candle on entry day (bounce confirmed)
        if c <= today[1]: continue

        # Volume above 20-day average (conviction)
        avg_vol = sum(rows[j][5] for j in range(max(0, i - 20), i)) / 20
        if avg_vol <= 0 or v < avg_vol: continue

        # Near 20-day low
        low_20 = min(rows[j][3] for j in range(max(0, i - 20), i + 1))
        if low_20 <= 0: continue
        dist_low = (c - low_20) / low_20 * 100
        if dist_low > 5: continue  # Must be within 5% of 20-day low

        # Above 200 SMA (long-term uptrend)
        if i >= 200:
            sma200 = sum(rows[j][4] for j in range(i - 199, i + 1)) / 200
            if c <= sma200: continue

        entry = c
        target = entry * 1.08   # 8% target (oversold bounce)
        sl = entry * 0.96       # 4% stop

        # Hold up to 10 days
        exit_px = entry
        ext = 'TIME'
        for d in range(1, min(11, len(rows) - i)):
            nd = rows[i + d]
            if nd[2] >= target:
                exit_px = target; ext = 'TARGET'; break
            if nd[3] <= sl:
                exit_px = sl; ext = 'SL'; break
            exit_px = nd[4]
            ext = 'EOD'

        net = CAPITAL * (exit_px - entry) / entry - BROKER
        oversold_trades.append(Trade(sym, entry, exit_px, net, today[0], ext, 'OVERSOLD'))

conn.close()

if not oversold_trades:
    print("NO TRADES — try loosening RSI threshold")
else:
    wins = [t for t in oversold_trades if t.pnl > 0]
    losses = [t for t in oversold_trades if t.pnl <= 0]
    total = sum(t.pnl for t in oversold_trades)
    wr = len(wins) / len(oversold_trades) * 100
    pf = sum(t.pnl for t in wins) / abs(sum(t.pnl for t in losses)) if losses else 999
    avg_win = sum(t.pnl for t in wins) / max(1, len(wins))
    avg_loss = sum(t.pnl for t in losses) / max(1, len(losses))

    print(f"\n{'='*70}")
    print(f"OVERSOLD BOUNCE (POSITIONAL) — Daily Candles, 3 months, Rs.{CAPITAL:,}/trade")
    print(f"{'='*70}")
    print(f"Trades:         {len(oversold_trades)}")
    print(f"Wins/Losses:    {len(wins)} / {len(losses)}")
    print(f"Win Rate:       {wr:.1f}%")
    print(f"Net PnL:        Rs.{total:,.0f}")
    print(f"Monthly PnL:    Rs.{total/3:,.0f}")
    print(f"Avg Win:        Rs.{avg_win:,.0f}")
    print(f"Avg Loss:       Rs.{avg_loss:,.0f}")
    print(f"Profit Factor:  {pf:.2f}")

    # Exits
    ec = collections.Counter(t.exit_type for t in oversold_trades)
    print(f"Exits:          {dict(ec)}")

    # Drawdown
    peak = dd = 0.0
    for t in oversold_trades:
        peak += t.pnl
        if peak > 0: peak = 0
        dd = min(dd, peak)
    print(f"Max Drawdown:   Rs.{-dd:,.0f}")

    # Monthly
    monthly = {}
    for t in oversold_trades:
        m = t.entry_time.strftime("%Y-%m")
        monthly[m] = monthly.get(m, 0) + t.pnl
    for m, p in sorted(monthly.items()):
        print(f"  {m}: Rs.{p:,.0f}")

    # RSI distribution of winners vs losers
    print(f"\nRSI-14 analysis:")
    wins_rsi = []
    losses_rsi = []
    for t in oversold_trades:
        # Find RSI at entry (rough)
        wins_rsi.append(t.pnl if t.pnl > 0 else 0)
        losses_rsi.append(t.pnl if t.pnl <= 0 else 0)

    print(f"User (75%): Rs.{total*0.75:,.0f} = Rs.{total*0.75/3:,.0f}/mo")
    if total > 0:
        print(f"ROI (Rs.50K): {total*0.75/50000*100/3:.1f}%/mo")

    # Comparison
    print(f"\n{'='*70}")
    print(f"COMPARISON TABLE")
    print(f"{'='*70}")
    print(f"{'Strategy':<28} {'Trades':>7} {'WR':>6} {'Net P&L':>10} {'/Mo':>8}")
    print(f"{'Oversold Bounce (1-min)':<28} {'5,976':>7} {'30%':>6} {'-Rs.214,680':>10} {'DISASTER':>8}")
    print(f"{'Oversold Bounce (DAILY)':<28} {len(oversold_trades):>7} {wr:>5.0f}% {total:>+10,.0f} {total/3:>+8,.0f}")

    print(f"\n{'3D Swing (Daily)':<28} {'176':>7} {'52%':>6} {'+Rs.8,303':>10} {'+2,768':>8}")
