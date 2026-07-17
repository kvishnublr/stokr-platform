"""Oversold Bounce - NIFTY 100, Daily Candles, Full Trade Report."""
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

# Only test symbols with daily data
placeholders = ','.join(['%s'] * len(NIFTY100))
cur.execute(f"SELECT DISTINCT symbol FROM candle_data WHERE timeframe='daily' AND symbol IN ({placeholders})", NIFTY100)
available = set(r[0] for r in cur.fetchall())
symbols = [s for s in NIFTY100 if s in available]

THREE_MO = datetime.datetime.now() - datetime.timedelta(days=90)
CAPITAL = 25000; BROKER = 40

all_trades = []
total_pnl = 0; wins = losses = 0

for sym in sorted(symbols):
    cur.execute("""SELECT timestamp, open, high, low, close, volume
        FROM candle_data WHERE symbol=%s AND timeframe='daily' AND timestamp >= %s
        ORDER BY timestamp""", (sym, THREE_MO))
    rows = [(r[0], float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in cur.fetchall()]
    if len(rows) < 30: continue

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
        if rsi >= 30: continue
        if c <= today[1]: continue  # Must be green

        avg_vol = sum(rows[j][5] for j in range(max(0, i - 20), i)) / 20
        if avg_vol <= 0 or v < avg_vol: continue

        low20 = min(rows[j][3] for j in range(max(0, i - 20), i + 1))
        if low20 <= 0: continue
        if (c - low20) / low20 * 100 > 5: continue

        if i >= 200:
            sma200 = sum(rows[j][4] for j in range(i - 199, i + 1)) / 200
            if c <= sma200: continue

        entry = c; target = entry * 1.08; sl = entry * 0.96
        exit_px = entry; exit_dt = dt; ext = 'TIME'

        for d in range(1, min(11, len(rows) - i)):
            nd = rows[i + d]
            if nd[2] >= target:
                exit_px = target; exit_dt = nd[0]; ext = 'TARGET'; break
            if nd[3] <= sl:
                exit_px = sl; exit_dt = nd[0]; ext = 'SL'; break
            exit_px = nd[4]; exit_dt = nd[0]; ext = 'EOD'

        gross = CAPITAL * (exit_px - entry) / entry
        net = gross - BROKER
        held = (exit_dt - dt).days

        total_pnl += net
        if net > 0: wins += 1
        else: losses += 1

        all_trades.append({
            'sym':sym,'entry_dt':dt,'entry':entry,'exit_dt':exit_dt,
            'exit':exit_px,'sl':sl,'tgt':target,'held':held,
            'ext':ext,'pnl':net,'rsi':rsi
        })

conn.close()

# Print header
print(f"NIFTY 100 symbols with data: {len(symbols)}\n")
print(f"{'='*110}")
print(f"OVERSOLD BOUNCE - NIFTY 100 - TRADE-BY-TRADE REPORT")
print(f"Period: {THREE_MO.strftime('%d-%b-%Y')} to now | Capital: Rs.{CAPITAL:,} | Brokerage: Rs.{BROKER}")
print(f"{'='*110}")
print(f"{'#':>3} {'Stock':<15} {'Entry Time':>13} {'Entry':>8} {'Exit Time':>13} {'Exit':>8} {'SL':>8} {'Tgt':>8} {'Days':>5} {'Type':>6} {'P&L':>9} {'Running':>10}")
print(f"{'-'*110}")
cumul = 0
for idx, t in enumerate(all_trades):
    cumul += t['pnl']
    print(f"{idx+1:>3} {t['sym']:<15} {t['entry_dt'].strftime('%d-%b %H:%M'):>13} {t['entry']:>8.1f} {t['exit_dt'].strftime('%d-%b %H:%M'):>13} {t['exit']:>8.1f} {t['sl']:>8.1f} {t['tgt']:>8.1f} {t['held']:>5}d {t['ext']:>6} {t['pnl']:>+8.0f} {cumul:>+9.0f}")

# SUMMARY
print(f"\n{'='*110}")
print(f"SUMMARY")
print(f"{'='*110}")
n = len(all_trades)
w = [t for t in all_trades if t['pnl'] > 0]
l = [t for t in all_trades if t['pnl'] <= 0]
print(f"Trades: {n} | Wins: {len(w)} | Losses: {len(l)} | WR: {len(w)/n*100:.1f}%" if n else "NO TRADES")
if n:
    print(f"Net P&L: Rs.{total_pnl:,.0f} | Monthly: Rs.{total_pnl/3:,.0f}")
    print(f"Avg Win: Rs.{sum(t['pnl'] for t in w)/max(1,len(w)):,.0f} | Avg Loss: Rs.{sum(t['pnl'] for t in l)/max(1,len(l)):,.0f}")
    if l:
        pf = sum(t['pnl'] for t in w)/abs(sum(t['pnl'] for t in l))
        print(f"Profit Factor: {pf:.2f}")

    # DD
    peak = dd = 0.0
    for t in all_trades:
        peak += t['pnl']
        if peak > 0: peak = 0
        dd = min(dd, peak)
    print(f"Max Drawdown: Rs.{-dd:,.0f}")

    # Exits
    ec = collections.Counter(t['ext'] for t in all_trades)
    print(f"\nExit Types: {dict(ec)}")

    # Symbol performance
    print(f"\nPer-Symbol:")
    print(f"{'Stock':<15} {'T':>3} {'W':>3} {'WR':>5} {'P&L':>10}")
    by_sym = {}
    for t in all_trades:
        s = t['sym']
        if s not in by_sym: by_sym[s] = {'t':0,'w':0,'p':0}
        by_sym[s]['t'] += 1
        by_sym[s]['p'] += t['pnl']
        if t['pnl'] > 0: by_sym[s]['w'] += 1
    for sym, d in sorted(by_sym.items(), key=lambda x: -x[1]['p']):
        wr = d['w']/d['t']*100 if d['t'] else 0
        print(f"{sym:<15} {d['t']:>3} {d['w']:>3} {wr:>4.0f}% {d['p']:>+10,.0f}")
