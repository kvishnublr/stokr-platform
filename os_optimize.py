"""Oversold Bounce Optimizer - Test multiple R:R + universe combos.
Tests: NIFTY 100 + Mid-caps + different target/SL/hold combos.
"""
import psycopg2, datetime, collections

conn = psycopg2.connect("dbname=stokr_lite user=postgres host=/var/run/postgresql")
cur = conn.cursor()

NIFTY100 = [
    "RELIANCE","TCS","HDFCBANK","INFY","ICICIBANK","HINDUNILVR","KOTAKBANK","ITC",
    "SBIN","BHARTIARTL","BAJFINANCE","ASIANPAINT","MARUTI","SUNPHARMA","TITAN",
    "AXISBANK","LT","DMART","ULTRACEMCO","WIPRO","POWERGRID","NTPC","HCLTECH",
    "TECHM","M&M","BAJAJFINSV","ADANIPORTS","GRASIM","INDUSINDBK","SHREECEM",
    "ONGC","NESTLE","JSWSTEEL","TATASTEEL","HINDALCO","COALINDIA","DIVISLAB",
    "DRREDDY","CIPLA","ADANIENT","EICHERMOT","BRITANNIA","APOLLOHOSP",
    "HEROMOTOCO","TATAMOTORS","PIDILITIND","BAJAJ-AUTO","HDFCLIFE","SBILIFE",
    "VEDL","UPL","TATACONSUM","BPCL","IOC","SRTRANSFIN",
    "GODREJCP","BERGEPAINT","SIEMENS","PAGEIND","HAVELLS","COLPAL","DABUR",
    "BIOCON","TVSMOTOR","MARICO","ICICIGI","LTIM","BOSCHLTD","BANKBARODA",
    "GAIL","TRENT","BEL","HAL","ZOMATO","IRCTC","TATAPOWER",
    "JINDALSTEL","CHOLAFIN","AMBUJACEM","TATACOMM","BHARATFORG","MOTHERSON",
    "DLF","BAJAJHLDNG","AUROPHARMA","TORNTPHARM","ALKEM","LUPIN","ABB",
]

THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)
CAPITAL = 25000; BROKER = 40

def get_daily_data(symbols):
    data = {}
    for sym in symbols:
        cur.execute("""SELECT timestamp, open, high, low, close, volume
            FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
            ORDER BY timestamp""", (sym, THREE_MO))
        rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
                for r in cur.fetchall()]
        if len(rows) >= 30:
            data[sym] = rows
    return data

def run_backtest(data, target_pct, sl_pct, hold_days, rsi_thresh=30, min_vol_ratio=1.0):
    trades = []
    total_pnl = 0; wins = 0; losses = 0

    for sym, rows in data.items():
        for i in range(29, len(rows) - 3):
            today = rows[i]; c, v, dt = today[4], today[5], today[0]

            # RSI14
            window = rows[i - 13:i + 1]
            gains, loss = 0.0, 0.0
            for j in range(1, len(window)):
                ch = window[j][4] - window[j - 1][4]
                if ch > 0: gains += ch
                else: loss += abs(ch)
            rsi = 100 - (100 / (1 + (gains / max(0.0001, loss))))
            if rsi >= rsi_thresh: continue
            if c <= today[1]: continue  # Green candle

            avg_vol = sum(rows[j][5] for j in range(max(0, i - 20), i)) / 20
            if avg_vol <= 0 or v < avg_vol * min_vol_ratio: continue

            low20 = min(rows[j][3] for j in range(max(0, i - 20), i + 1))
            if low20 <= 0: continue
            if (c - low20) / low20 * 100 > 5: continue

            if i >= 200:
                sma200 = sum(rows[j][4] for j in range(i - 199, i + 1)) / 200
                if c <= sma200: continue

            entry = c; target = entry * (1 + target_pct/100); sl = entry * (1 - sl_pct/100)
            exit_px = entry; ext = 'TIME'

            for d in range(1, min(hold_days + 1, len(rows) - i)):
                nd = rows[i + d]
                if nd[2] >= target:
                    exit_px = target; ext = 'TARGET'; break
                if nd[3] <= sl:
                    exit_px = sl; ext = 'SL'; break
                exit_px = nd[4]; ext = 'EOD'

            net = CAPITAL * (exit_px - entry) / entry - BROKER
            total_pnl += net
            if net > 0: wins += 1
            else: losses += 1
            trades.append(net)

    return trades, total_pnl, wins, losses

# Load all available daily symbols (larger universe = more signals)
cur.execute("SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily'")
all_symbols = [r[0] for r in cur.fetchall()]
nifty100_available = [s for s in NIFTY100 if s in all_symbols]
midcaps = [s for s in all_symbols if s not in nifty100_available]

print(f"NIFTY 100: {len(nifty100_available)} | Mid/Small: {len(midcaps)} | Total: {len(all_symbols)}")

nifty_data = get_daily_data(nifty100_available)
midcap_data = get_daily_data(midcaps)
all_data = {**nifty_data, **midcap_data}

# Test matrix: universe x target x SL x hold
configs = [
    ("NIFTY 100", nifty_data, 5, 3, 10),
    ("NIFTY 100", nifty_data, 4, 2.5, 10),
    ("NIFTY 100", nifty_data, 3, 2, 7),
    ("NIFTY 100", nifty_data, 6, 3, 14),
    ("All Stocks", all_data, 5, 3, 10),
    ("All Stocks", all_data, 4, 2.5, 10),
    ("All Stocks", all_data, 3, 2, 7),
    ("All Stocks", all_data, 6, 4, 14),
    ("All Stocks", all_data, 5, 3, 14),
]

print(f"\n{'='*100}")
print(f"OVERSOLD BOUNCE OPTIMIZER — {len(all_data)} symbols, 3 months, Rs.{CAPITAL:,}/trade")
print(f"{'='*100}")
print(f"{'Universe':<15} {'Tgt%':>5} {'SL%':>5} {'Hold':>5} {'Trades':>7} {'WR':>6} {'Net P&L':>10} {'/Mo':>8} {'PF':>5} {'AvgW':>7} {'AvgL':>7}")
print(f"{'-'*100}")

best = (None, -999999)

for name, data, tgt, sl, hold in configs:
    trades, pnl, wins, losses = run_backtest(data, tgt, sl, hold)
    n = len(trades)
    if n == 0: continue
    wr = wins / n * 100
    mo = pnl / 3
    pf = sum(t for t in trades if t > 0) / abs(sum(t for t in trades if t <= 0)) if losses else 999
    aw = sum(t for t in trades if t > 0) / max(1, wins)
    al = sum(t for t in trades if t <= 0) / max(1, losses)

    print(f"{name:<15} {tgt:>5}% {sl:>5}% {hold:>5}d {n:>7} {wr:>5.0f}% {pnl:>+9,.0f} {mo:>+7,.0f} {pf:>4.1f}x {aw:>+6,.0f} {al:>+6,.0f}")

    if pnl > best[1]:
        best = ((name, tgt, sl, hold, wr, pnl, mo, n, wins, losses), pnl)

if best[0]:
    n, t, s, h, wr, pnl, mo, tr, w, l = best[0]
    print(f"\nBEST: {n} {t}%/{s}% {h}d hold = {tr}t {wr:.0f}% WR Rs.{pnl:,.0f} ({mo:,.0f}/mo)")

# RSI threshold variation
print(f"\n{'='*100}")
print(f"RSI THRESHOLD OPTIMIZATION (All Stocks, 5%/3% SL, 10d hold)")
print(f"{'='*100}")
for rsi_t in [30, 28, 25, 22, 20]:
    trades, pnl, wins, losses = run_backtest(all_data, 5, 3, 10, rsi_thresh=rsi_t)
    n = len(trades)
    if n == 0: continue
    wr = wins / n * 100
    print(f"  RSI<{rsi_t}: {n:>4}t {wr:>5.0f}% WR Rs.{pnl:>+9,.0f} = Rs.{pnl/3:>+7,.0f}/mo")

# Volume threshold variation
print(f"\n{'='*100}")
print(f"VOLUME THRESHOLD OPTIMIZATION (All Stocks, 5%/3% SL, 10d hold)")
print(f"{'='*100}")
for vol in [1.0, 1.2, 1.5, 2.0]:
    trades, pnl, wins, losses = run_backtest(all_data, 5, 3, 10, min_vol_ratio=vol)
    n = len(trades)
    if n == 0: continue
    wr = wins / n * 100
    print(f"  Vol>{vol}x: {n:>4}t {wr:>5.0f}% WR Rs.{pnl:>+9,.0f} = Rs.{pnl/3:>+7,.0f}/mo")

conn.close()
