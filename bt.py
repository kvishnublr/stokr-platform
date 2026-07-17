"""Institutional Footprint Backtest — direct DB access."""
import psycopg2, datetime, collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='1min'")
symbols = [r[0] for r in cur.fetchall()]
print(f"Symbols: {len(symbols)}")

three_mo = datetime.datetime.now() - datetime.timedelta(days=90)
Trade = collections.namedtuple('Trade', 'symbol entry exit pnl entry_time exit_type')
all_trades = []

for si, sym in enumerate(symbols):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='1min' AND timestamp >= %s
        ORDER BY timestamp""", (sym, three_mo))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), int(r[5])) for r in cur.fetchall()]
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

            pv_sum = vol_sum = 0.0
            for c in window:
                tp = (float(c[1]) + float(c[2]) + float(c[4])) / 3.0
                pv_sum += tp * c[5]
                vol_sum += c[5]
            vwap = pv_sum / vol_sum if vol_sum > 0 else window[-1][4]

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

            # Score 1: VSA
            lb = min(20, cn - 1)
            avg_vol = sum(window[j][5] for j in range(cn - lb - 1, cn - 1)) / max(1, lb)
            avg_spread = sum(
                (window[j][2] - window[j][3]) / window[j][4] * 100
                for j in range(cn - lb - 1, cn - 1)
            ) / max(1, lb)

            vol_ratio = latest[5] / max(1, avg_vol)
            spread_pct = (latest[2] - latest[3]) / latest[4] * 100
            is_green = latest[4] > latest[1]

            session_low = min(window[j][3] for j in range(max(0, cn - 40), cn))
            if session_low <= 0: continue
            dist_low = (entry_px - session_low) / session_low * 100

            vsa_score = 0
            if vol_ratio < 0.6 and spread_pct < 0.3 and dist_low < 0.5:
                vsa_score = 30
            elif vol_ratio > 2.5 and spread_pct > 1.0 and is_green:
                vsa_score = 25
            elif vol_ratio > 2.0 and spread_pct < 1.0 and dist_low < 1.0:
                vsa_score = 20
            elif vol_ratio > 1.3 and is_green and spread_pct > avg_spread * 0.8:
                vsa_score = 15

            # Score 3: Order flow
            up_vol = sum(window[j][5] for j in range(cn - lb, cn) if window[j][4] > window[j][1])
            dn_vol = sum(window[j][5] for j in range(cn - lb, cn) if window[j][4] < window[j][1])
            of_ratio = up_vol / max(1, dn_vol)
            if of_ratio >= 2.0:
                of_score = 10
            elif of_ratio >= 1.5:
                of_score = 6
            elif of_ratio >= 1.0:
                of_score = 3
            else:
                of_score = 0

            # Score 4: Setup
            setup = 0
            vwap_dist = (entry_px - vwap) / vwap * 100
            if 0 <= vwap_dist <= 0.3:
                setup += 8
            elif 0 <= vwap_dist <= 0.6:
                setup += 6
            elif 0 <= vwap_dist <= 1.0:
                setup += 4
            elif vwap_dist >= 0:
                setup += 2

            session_high = max(window[j][2] for j in range(max(0, cn - 40), cn))
            if session_high - session_low > 0 and entry_px > (session_high + session_low) / 2:
                setup += 3

            day_open = window[0][1]
            if day_open <= 0: continue
            day_chg = (entry_px - day_open) / day_open * 100
            if day_chg > 3.0:
                setup -= 5
            if day_chg < -2.0:
                setup -= 3
            setup = max(0, min(20, setup))

            total = vsa_score + of_score + setup
            if total < 60:
                continue

            # Exit simulation
            atr = sum(window[j][2] - window[j][3] for j in range(cn - 14, cn)) / 14 if cn >= 14 else entry_px * 0.005
            sl = entry_px - 2 * atr
            target = entry_px + 3 * atr
            if (entry_px - sl) / entry_px * 100 > 1.2:
                sl = entry_px * 0.988
            if (target - entry_px) / entry_px * 100 > 3.5:
                target = entry_px * 1.035

            peak = entry_px
            trail = False
            exit_px = entry_px
            ext = 'TIME'

            for j in range(i + 1, min(i + 60, n)):
                c = candles[j]
                if c[2] > peak:
                    peak = c[2]
                if not trail and (peak - entry_px) / entry_px * 100 >= 1.2:
                    trail = True
                eff_sl = peak * 0.992 if trail else sl
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
            all_trades.append(Trade(sym, entry_px, exit_px, net, t, ext))

    if (si + 1) % 50 == 0:
        print(f"  {si + 1}/{len(symbols)} symbols, {len(all_trades)} trades")

conn.close()

if not all_trades:
    print("NO TRADES FOUND")
    import sys; sys.exit()

wins = [t for t in all_trades if t.pnl > 0]
losses = [t for t in all_trades if t.pnl <= 0]
total_pnl = sum(t.pnl for t in all_trades)

print(f"\n{'=' * 50}")
print(f"INSTITUTIONAL FOOTPRINT BACKTEST RESULTS (3 months)")
print(f"{'=' * 50}")
print(f"Total Trades:   {len(all_trades)}")
print(f"Wins:           {len(wins)}")
print(f"Losses:         {len(losses)}")
print(f"Win Rate:       {len(wins) / len(all_trades) * 100:.1f}%")
print(f"Total Net PnL:  Rs.{total_pnl:,.0f}")
print(f"Monthly PnL:    Rs.{total_pnl / 3:,.0f}")
print(f"Avg Win:        Rs.{sum(t.pnl for t in wins) / max(1, len(wins)):,.0f}")
print(f"Avg Loss:       Rs.{sum(t.pnl for t in losses) / max(1, len(losses)):,.0f}")
if losses:
    pf = sum(t.pnl for t in wins) / abs(sum(t.pnl for t in losses))
    print(f"Profit Factor:  {pf:.2f}")

peak = dd = 0
for t in all_trades:
    peak += t.pnl
    if peak > 0: peak = 0
    dd = min(dd, peak)
print(f"Max Drawdown:   Rs.{-dd:,.0f}")

# Exit breakdown
ec = collections.Counter(t.exit_type for t in all_trades)
print(f"\nExit Types: {dict(ec)}")

# Top/bottom symbols
for label, reverse in [("Best", True), ("Worst", False)]:
    by_sym = {}
    for t in all_trades:
        by_sym.setdefault(t.symbol, []).append(t.pnl)
    sorted_syms = sorted(by_sym.items(), key=lambda x: sum(x[1]), reverse=reverse)[:5]
    print(f"{label} symbols: {[(s, int(sum(p))) for s, p in sorted_syms]}")

# Monthly breakdown
monthly = {}
for t in all_trades:
    m = t.entry_time.strftime("%Y-%m")
    monthly[m] = monthly.get(m, 0) + t.pnl
print(f"\nMonthly PnL: {dict(sorted(monthly.items()))}")

print(f"\nUser Profit (75%): Rs.{total_pnl * 0.75:,.0f}/month = Rs.{total_pnl * 0.75 / 3:,.0f}")
print(f"Admin Fee (25%):   Rs.{total_pnl * 0.25:,.0f}/month = Rs.{total_pnl * 0.25 / 3:,.0f}")
print(f"User ROI on Rs.50K: {total_pnl * 0.75 / 50000 * 100 / 3:.1f}%/month")
