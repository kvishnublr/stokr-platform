"""INVERSE SIGNAL ENGINE — Flip our losing strategies.

If our 4-strategy confluence gave 17% WR on BUY signals,
then SELL signals should give ~83% WR pre-brokerage.

Backtest: whenever 2+ strategies say BUY → SHORT instead.
Exit with the same parameters but inverted.
"""
import psycopg2, datetime, collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min'")
symbols = [r[0] for r in cur.fetchall()]
print(f"Scanning {len(symbols)} symbols for INVERSE signals...")

three_mo = datetime.datetime.now() - datetime.timedelta(days=90)
Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type')
all_trades = []

for si, sym in enumerate(symbols):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='1min' AND timestamp >= %s
        ORDER BY timestamp""", (sym, three_mo))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 300:
        continue

    by_date = {}
    for r in rows:
        by_date.setdefault(r[0].date(), []).append(r)

    for date, candles in by_date.items():
        n = len(candles)
        if n < 40:
            continue

        for i in range(40, n, 5):
            window = candles[:i + 1]
            cn = len(window)
            latest = window[-1]
            entry_px = latest[4]
            if entry_px < 80 or entry_px > 5000:
                continue

            t = latest[0]
            total_min = t.hour * 60 + t.minute
            if total_min < 9 * 60 + 45 or total_min > 14 * 60 + 30:
                continue
            if 11 * 60 + 30 <= total_min < 13 * 60:
                continue

            lb = min(20, cn - 1)
            if lb < 5: continue
            avg_vol = sum(window[j][5] for j in range(cn - lb - 1, cn - 1)) / lb
            if avg_vol <= 0: continue

            vol_ratio = latest[5] / avg_vol
            spread_pct = (latest[2] - latest[3]) / entry_px * 100
            is_green = latest[4] > latest[1]
            body_pct = abs(latest[4] - latest[1]) / entry_px * 100

            session_low = min(window[j][3] for j in range(max(0, cn - 40), cn))
            session_high = max(window[j][2] for j in range(max(0, cn - 40), cn))
            if session_low <= 0: continue
            dist_low = (entry_px - session_low) / session_low * 100

            # ━━━ Score: same logic as Institutional Footprint ━━━
            vsa = 0
            if vol_ratio < 0.6 and spread_pct < 0.15 and dist_low < 0.5 and is_green:
                vsa = 25
            elif vol_ratio > 1.0 and is_green and body_pct > spread_pct * 0.4:
                vsa = 15
            elif vol_ratio > 2.0 and spread_pct > 0.2 and is_green:
                vsa = 20
            elif vol_ratio < 0.8 and spread_pct < 0.2 and dist_low < 1.0:
                vsa = 10

            up_vol = sum(window[j][5] for j in range(cn - lb, cn) if window[j][4] > window[j][1])
            dn_vol = sum(window[j][5] for j in range(cn - lb, cn) if window[j][4] < window[j][1])
            of_ratio = dn_vol / max(1, up_vol)  # INVERTED: bearish order flow
            of_score = 15 if of_ratio >= 2.0 else (10 if of_ratio >= 1.5 else
                        (6 if of_ratio >= 1.2 else (3 if of_ratio >= 1.0 else 0)))

            # VWAP
            pv_sum = vol_sum = 0.0
            for c in window:
                tp = (c[1] + c[2] + c[4]) / 3.0
                pv_sum += tp * c[5]
                vol_sum += c[5]
            vwap = pv_sum / vol_sum if vol_sum > 0 else entry_px

            setup = 0
            vwap_dist = (entry_px - vwap) / vwap * 100 if vwap > 0 else 0
            if -0.5 <= vwap_dist <= 0.2: setup += 8
            elif -1.0 <= vwap_dist <= 0.5: setup += 5
            elif vwap_dist <= 0: setup += 2

            sr = session_high - session_low
            if sr > 0:
                pos = (entry_px - session_low) / sr
                if 0.2 <= pos <= 0.6: setup += 7  # INVERTED: weaker half = bearish
                elif pos < 0.2: setup += 3

            day_open = window[0][1]
            if day_open > 0:
                day_chg = (entry_px - day_open) / day_open * 100
                if 0 <= day_chg <= 3.0: setup += 5
                elif day_chg > 3.0: setup += 8  # INVERTED: overextended = short

            setup = max(0, min(25, setup))
            total = vsa + of_score + setup

            if total < 40: continue  # Lower threshold for inverse

            # ━━━ SHORT signal confirmed ━━━
            atr = sum(window[j][2] - window[j][3] for j in range(cn - 14, cn)) / 14
            if atr <= 0: atr = entry_px * 0.003
            sl = entry_px + 1.5 * atr
            target = entry_px - 2.5 * atr
            if (sl - entry_px) / entry_px > 0.01: sl = entry_px * 1.01
            if (entry_px - target) / entry_px > 0.025: target = entry_px * 0.975

            trough = entry_px
            trail = False
            exit_px = entry_px
            ext = 'TIME'

            for j in range(i + 1, min(i + 45, n)):
                c = candles[j]
                if c[3] < trough: trough = c[3]
                if (entry_px - trough) / entry_px >= 0.01: trail = True
                eff_sl = min(trough * 1.006 if trail else sl, sl)
                if c[2] >= eff_sl:
                    exit_px = eff_sl
                    ext = 'TRAIL' if trail else 'SL'
                    break
                if c[3] <= target:
                    exit_px = target
                    ext = 'TARGET'
                    break

            gross = 25000 * (entry_px - exit_px) / entry_px  # Short P&L
            net = gross - 40
            all_trades.append(Trade(sym, entry_px, exit_px, net, t, ext))

    if (si + 1) % 75 == 0:
        print(f"  {si + 1}/{len(symbols)}, {len(all_trades)} trades")

conn.close()

if not all_trades:
    print("NO TRADES")
    import sys; sys.exit()

wins = [t for t in all_trades if t.pnl > 0]
losses = [t for t in all_trades if t.pnl <= 0]
total_pnl = sum(t.pnl for t in all_trades)

print(f"\n{'='*55}")
print(f"INVERSE SIGNAL ENGINE — SHORT on buy confluence")
print(f"{'='*55}")
print(f"Trades:         {len(all_trades)}")
print(f"Win Rate:       {len(wins)/len(all_trades)*100:.1f}%")
print(f"Net PnL:        Rs.{total_pnl:,.0f}")
print(f"Monthly:        Rs.{total_pnl/3:,.0f}")
print(f"Avg Win:        Rs.{sum(t.pnl for t in wins)/max(1,len(wins)):,.0f}")
print(f"Avg Loss:       Rs.{sum(t.pnl for t in losses)/max(1,len(losses)):,.0f}")
if losses:
    pf = sum(t.pnl for t in wins)/abs(sum(t.pnl for t in losses))
    print(f"Profit Factor:  {pf:.2f}")

# Drawdown
peak = dd = 0
for t in all_trades:
    peak += t.pnl
    if peak > 0: peak = 0
    dd = min(dd, peak)
print(f"Max DD:         Rs.{-dd:,.0f}")

ec = collections.Counter(t.exit_type for t in all_trades)
print(f"Exits:          {dict(ec)}")

monthly = {}
for t in all_trades:
    m = t.entry_time.strftime("%Y-%m")
    monthly[m] = monthly.get(m, 0) + t.pnl
for m, p in sorted(monthly.items()):
    print(f"  {m}: Rs.{p:,.0f}")

print(f"\nUser (75%):  Rs.{total_pnl*0.75:,.0f} = Rs.{total_pnl*0.75/3:,.0f}/mo")
if total_pnl > 0:
    print(f"ROI (50K):   {total_pnl*0.75/50000*100/3:.1f}%/mo")
