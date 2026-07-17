"""Realistic Institutional Footprint — thresholds tuned to actual market data."""
import psycopg2, datetime, collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min'")
symbols = [r[0] for r in cur.fetchall()]
print(f"Scanning {len(symbols)} symbols...")

three_mo = datetime.datetime.now() - datetime.timedelta(days=90)
Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type score')
all_trades = []

for si, sym in enumerate(symbols):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='1min' AND timestamp >= %s
        ORDER BY timestamp""", (sym, three_mo))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), int(r[5]))
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
            if total_min < 9 * 60 + 45 or total_min > 14 * 60 + 45:
                continue
            if 11 * 60 + 30 <= total_min < 13 * 60:
                continue

            # Compute VWAP
            pv_sum = vol_sum = 0.0
            for c in window:
                tp = (c[1] + c[2] + c[4]) / 3.0
                pv_sum += tp * c[5]
                vol_sum += c[5]
            vwap = pv_sum / vol_sum if vol_sum > 0 else entry_px

            # 20-period stats
            lb = min(20, cn - 1)
            if lb < 5:
                continue
            avg_vol = sum(window[j][5] for j in range(cn - lb - 1, cn - 1)) / lb
            if avg_vol <= 0:
                continue

            vol_ratio = latest[5] / avg_vol
            spread_pct = (latest[2] - latest[3]) / entry_px * 100
            is_green = latest[4] > latest[1]
            body_pct = abs(latest[4] - latest[1]) / entry_px * 100

            session_low = min(window[j][3] for j in range(max(0, cn - 40), cn))
            session_high = max(window[j][2] for j in range(max(0, cn - 40), cn))
            if session_low <= 0:
                continue
            dist_low = (entry_px - session_low) / session_low * 100

            # ━━━ VSA Score (0-30) — TUNED ━━━
            vsa = 0
            # Pattern 1: Drying volume near session low → accumulation
            if vol_ratio < 0.6 and spread_pct < 0.15 and dist_low < 0.5 and is_green:
                vsa = 25
            # Pattern 2: Normal volume + green candle with body → bullish
            elif vol_ratio > 1.0 and is_green and body_pct > spread_pct * 0.4:
                vsa = 15
            # Pattern 3: Volume spike with wide range → climax
            elif vol_ratio > 2.0 and spread_pct > 0.2 and is_green:
                vsa = 20
            # Pattern 4: Mild accumulation
            elif vol_ratio < 0.8 and spread_pct < 0.2 and dist_low < 1.0:
                vsa = 10

            # ━━━ Order Flow (0-25) ━━━
            up_vol = sum(window[j][5] for j in range(cn - lb, cn)
                         if window[j][4] > window[j][1])
            dn_vol = sum(window[j][5] for j in range(cn - lb, cn)
                         if window[j][4] < window[j][1])
            of_ratio = up_vol / max(1, dn_vol)
            of_score = 15 if of_ratio >= 2.0 else (10 if of_ratio >= 1.5 else
                        (6 if of_ratio >= 1.2 else (3 if of_ratio >= 1.0 else 0)))

            # ━━━ Setup Quality (0-25) ━━━
            setup = 0
            # VWAP position
            if vwap > 0:
                vwap_dist = (entry_px - vwap) / vwap * 100
                if -0.2 <= vwap_dist <= 0.5:
                    setup += 8
                elif -0.5 <= vwap_dist <= 1.0:
                    setup += 5
                elif vwap_dist >= 0:
                    setup += 2

            # Session position
            sr = session_high - session_low
            if sr > 0:
                pos = (entry_px - session_low) / sr
                if 0.4 <= pos <= 0.8:
                    setup += 7  # middle of range = healthy
                elif pos > 0.8:
                    setup += 3  # near high = momentum, but maybe extended

            # Day change check
            day_open = window[0][1]
            if day_open > 0:
                day_chg = (entry_px - day_open) / day_open * 100
                if -0.5 <= day_chg <= 2.5:
                    setup += 7  # not too far from open
                elif 2.5 < day_chg <= 4.0:
                    setup += 3
                elif day_chg > 4.0:
                    setup -= 3  # extended

            # Trend: last 3 candles direction
            if cn >= 3:
                c1, c2, c3 = window[-3], window[-2], window[-1]
                if c3[4] > c2[4] > c1[4]:
                    setup += 3  # uptrend

            setup = max(0, min(25, setup))

            total = vsa + of_score + setup
            if total < 55:
                continue

            # ━━━ Exit Simulation ━━━
            atr = sum(window[j][2] - window[j][3] for j in range(cn - 14, cn)) / 14
            if atr <= 0:
                atr = entry_px * 0.003
            sl = entry_px - 1.5 * atr
            target = entry_px + 2.5 * atr
            if (entry_px - sl) / entry_px * 100 > 1.0:
                sl = entry_px * 0.99
            if (target - entry_px) / entry_px * 100 > 2.5:
                target = entry_px * 1.025

            peak = trail = entry_px
            exit_px = entry_px
            ext = 'TIME'

            for j in range(i + 1, min(i + 45, n)):
                c = candles[j]
                if c[2] > peak:
                    peak = c[2]
                if (peak - entry_px) / entry_px * 100 >= 1.0:
                    trail = peak
                eff_sl = max(trail * 0.994 if trail else sl, sl)
                if c[3] <= eff_sl:
                    exit_px = eff_sl
                    ext = 'TRAIL' if trail else 'SL'
                    break
                if c[2] >= target:
                    exit_px = target
                    ext = 'TARGET'
                    break

            gross = 25000 * (exit_px - entry_px) / entry_px
            net = gross - 40
            all_trades.append(Trade(sym, entry_px, exit_px, net, t, ext, total))

    if (si + 1) % 75 == 0:
        print(f"  {si + 1}/{len(symbols)} symbols, {len(all_trades)} trades")

conn.close()

if not all_trades:
    print("\nNO TRADES - score threshold too high!")
    import sys
    sys.exit()

wins = [t for t in all_trades if t.pnl > 0]
losses = [t for t in all_trades if t.pnl <= 0]
total_pnl = sum(t.pnl for t in all_trades)

print(f"\n{'=' * 55}")
print(f"INSTITUTIONAL FOOTPRINT BACKTEST (3 months, score >= 55)")
print(f"{'=' * 55}")
print(f"Total Trades:   {len(all_trades)}")
print(f"Wins/Losses:    {len(wins)} / {len(losses)}")
print(f"Win Rate:       {len(wins) / len(all_trades) * 100:.1f}%")
print(f"Total Net PnL:  Rs.{total_pnl:,.0f}")
print(f"Monthly PnL:    Rs.{total_pnl / 3:,.0f} (on Rs.50K deployed)")
print(f"Avg/Trade:      Rs.{total_pnl / len(all_trades):,.0f}")
print(f"Avg Win:        Rs.{sum(t.pnl for t in wins) / max(1, len(wins)):,.0f}")
print(f"Avg Loss:       Rs.{sum(t.pnl for t in losses) / max(1, len(losses)):,.0f}")

if losses:
    pf = sum(t.pnl for t in wins) / abs(sum(t.pnl for t in losses))
    print(f"Profit Factor:  {pf:.2f}")

# Drawdown
peak = dd = 0
for t in all_trades:
    peak += t.pnl
    if peak > 0: peak = 0
    dd = min(dd, peak)
print(f"Max Drawdown:   Rs.{-dd:,.0f}")

# Score analysis
for sc in [75, 70, 65, 60, 55]:
    filtered = [t for t in all_trades if t.score >= sc]
    if filtered:
        w = len([t for t in filtered if t.pnl > 0])
        p = sum(t.pnl for t in filtered)
        print(f"  Score>={sc}: {len(filtered)}t, WR={w/len(filtered)*100:.0f}%, PnL=Rs.{p:,.0f}")

# Exit breakdown
ec = collections.Counter(t.exit_type for t in all_trades)
print(f"\nExit Types: {dict(ec)}")

# Monthly
monthly = {}
for t in all_trades:
    m = t.entry_time.strftime("%Y-%m")
    monthly[m] = monthly.get(m, 0) + t.pnl
for m, p in sorted(monthly.items()):
    print(f"  {m}: Rs.{p:,.0f}")

print(f"\nUser Profit (75%): Rs.{total_pnl * 0.75:,.0f} = Rs.{total_pnl * 0.75 / 3:,.0f}/mo")
print(f"Admin Fee (25%):   Rs.{total_pnl * 0.25:,.0f} = Rs.{total_pnl * 0.25 / 3:,.0f}/mo")
if total_pnl > 0:
    print(f"User ROI:          {total_pnl * 0.75 / 50000 * 100 / 3:.1f}%/month")
