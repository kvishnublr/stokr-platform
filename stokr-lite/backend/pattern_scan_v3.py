import psycopg2
from collections import defaultdict

conn = psycopg2.connect(host='localhost', dbname='stokr_lite', user='postgres', password='`$POSTGRES_PASSWORD')
cur = conn.cursor()

cur.execute("""
    SELECT symbol, count(*) as cnt FROM candle_data 
    WHERE timeframe = '1min' 
    GROUP BY symbol HAVING count(*) > 5000
    ORDER BY cnt DESC LIMIT 30
""")
symbols = [r[0] for r in cur.fetchall()]

cur.execute("""
    SELECT symbol, timestamp, open, high, low, close, volume
    FROM candle_data WHERE timeframe = '1min' AND symbol IN %s
    ORDER BY symbol, timestamp
""", (tuple(symbols),))

data = defaultdict(list)
for row in cur.fetchall():
    sym, ts, o, h, l, c, v = row
    data[sym].append({'ts': ts, 'open': float(o), 'high': float(h), 'low': float(l), 'close': float(c), 'volume': int(v or 0)})

def get_day_candles(candles):
    days = defaultdict(list)
    for c in candles: days[c['ts'].date()].append(c)
    return days

def ema(values, period):
    if len(values) < period: return values[-1]
    k = 2 / (period + 1)
    e = sum(values[:period]) / period
    for v in values[period:]: e = v * k + e * (1 - k)
    return e

BROKERAGE = 80
CAPITAL = 100000

def eval(name, trades):
    if not trades:
        print(f"  {name}: No trades"); return
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    total = sum(t['pnl'] for t in trades)
    wr = len(wins)/len(trades)*100
    pf = sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999
    aw = sum(t['pnl'] for t in wins)/len(wins) if wins else 0
    al = sum(t['pnl'] for t in losses)/len(losses) if losses else 0
    print(f"  {name:<35} T:{len(trades):>4} W:{len(wins):>3} WR:{wr:>5.1f}% Net:â‚¹{total:>+8,.0f} PF:{pf:>5.2f} AvgW:â‚¹{aw:>6,.0f} AvgL:â‚¹{al:>6,.0f}")

print("=" * 95)
print("INTRADAY PATTERN SCAN v3 â€” Momentum, bigger targets, time-filtered")
print("=" * 95)

# â•â•â• PATTERN 13: VWAP Extreme + RSI + Wait for Confirmation â•â•â•
print("\n--- VWAP + RSI CONFLUENCE PATTERNS ---")
print("-" * 95)

# 13a: RSI<15 + 2% below VWAP + next candle green â†’ BUY
trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        cum_pv = cum_v = 0
        closes = [c['close'] for c in dc]
        for i, c in enumerate(dc):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 20: continue
            vwap = cum_pv / cum_v
            # RSI(5)
            if i < 6: continue
            gains = [max(closes[j]-closes[j-1],0) for j in range(max(1,i-4), i+1)]
            losses_r = [max(closes[j-1]-closes[j],0) for j in range(max(1,i-4), i+1)]
            ag = sum(gains)/5; al_r = sum(losses_r)/5
            rsi5 = 100 - 100/(1+ag/al_r) if al_r > 0 else (100 if ag > 0 else 50)
            
            dev = (c['close'] - vwap) / vwap * 100
            
            # Long: RSI<15, 2% below VWAP, next candle green
            if rsi5 < 15 and dev < -2.0 and i+1 < len(dc) and dc[i+1]['close'] > dc[i+1]['open']:
                entry = dc[i+1]['close']
                sl = entry * 0.99  # 1% SL (wider for bigger target)
                target = entry * 1.01  # 1% target
                for j in range(i+2, min(i+20, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); break
                break
eval("RSI5<15 + 2% below VWAP + green confirm", trades)

# â•â•â• PATTERN 14: Strong Momentum (3 consecutive big candles, ride momentum) â•â•â•
print("\n--- MOMENTUM PATTERNS (ride, don't fade) ---")
print("-" * 95)

# 14a: 3 consecutive green candles each >0.2% â†’ BUY next, trail
trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        entered = False
        for i in range(20, len(dc) - 2):
            if entered: break
            c1, c2, c3 = dc[i-2], dc[i-1], dc[i]
            if (c1['close'] > c1['open'] and c2['close'] > c2['open'] and c3['close'] > c3['open']):
                # All 3 green, each >0.15%
                m1 = (c1['close'] - c1['open']) / c1['open'] * 100
                m2 = (c2['close'] - c2['open']) / c2['open'] * 100
                m3 = (c3['close'] - c3['open']) / c3['open'] * 100
                if m1 > 0.15 and m2 > 0.15 and m3 > 0.15:
                    entry = dc[i+1]['open']
                    sl = entry * 0.99  # 1% SL
                    target = entry * 1.015  # 1.5% target (bigger)
                    for j in range(i+2, min(i+30, len(dc))):
                        if dc[j]['low'] <= sl:
                            trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                        elif dc[j]['high'] >= target:
                            trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
eval("3-bar momentum LONG (>0.15% each)", trades)

# 14b: 3 consecutive red â†’ SHORT
trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        entered = False
        for i in range(20, len(dc) - 2):
            if entered: break
            c1, c2, c3 = dc[i-2], dc[i-1], dc[i]
            if (c1['close'] < c1['open'] and c2['close'] < c2['open'] and c3['close'] < c3['open']):
                m1 = (c1['open'] - c1['close']) / c1['open'] * 100
                m2 = (c2['open'] - c2['close']) / c2['open'] * 100
                m3 = (c3['open'] - c3['close']) / c3['open'] * 100
                if m1 > 0.15 and m2 > 0.15 and m3 > 0.15:
                    entry = dc[i+1]['open']
                    sl = entry * 1.01
                    target = entry * 0.985
                    for j in range(i+2, min(i+30, len(dc))):
                        if dc[j]['high'] >= sl:
                            trades.append({'pnl': (entry-sl)/entry*CAPITAL-BROKERAGE}); entered=True; break
                        elif dc[j]['low'] <= target:
                            trades.append({'pnl': (entry-target)/entry*CAPITAL-BROKERAGE}); entered=True; break
eval("3-bar momentum SHORT (>0.15% each)", trades)

# â•â•â• PATTERN 15: VWAP + EMA crossover â•â•â•
print("\n--- EMA + VWAP PATTERNS ---")
print("-" * 95)

# 15: Price crosses above both VWAP and EMA(9) simultaneously
trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        cum_pv = cum_v = 0
        closes = [c['close'] for c in dc]
        prev_above_vwap = prev_above_ema = None
        entered = False
        for i, c in enumerate(dc):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 20:
                prev_above_vwap = prev_above_ema = None; continue
            
            vwap = cum_pv / cum_v
            ema9 = ema(closes[:i+1], 9)
            
            above_vwap = c['close'] > vwap
            above_ema = c['close'] > ema9
            
            # Cross above BOTH
            if (prev_above_vwap == False and prev_above_ema == False and above_vwap and above_ema and not entered):
                entry = c['close']
                sl = entry * 0.995
                target = entry * 1.005
                for j in range(i+1, min(i+20, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
            prev_above_vwap = above_vwap
            prev_above_ema = above_ema
            entered = False
eval("VWAP + EMA9 cross above", trades)

# â•â•â• PATTERN 16: Price reclaim after sharp drop (micro V-reversal) â•â•â•
print("\n--- MICRO V-REVERSAL (sharp drop â†’ immediate reclaim) ---")
print("-" * 95)

# Price drops >0.5% in 3 candles, then next candle closes above entry
trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        entered = False
        for i in range(10, len(dc) - 2):
            if entered: break
            # 3-candle drop
            drop_start = dc[i-2]['open']
            drop_end = dc[i]['close']
            if drop_start <= 0: continue
            drop_pct = (drop_end - drop_start) / drop_start * 100
            
            if drop_pct < -0.5 and dc[i+1]['close'] > dc[i]['close']:
                # Reclaim â€” buy
                entry = dc[i+1]['close']
                sl = entry * 0.995
                target = entry * 1.005
                for j in range(i+2, min(i+15, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
            entered = False
eval("Micro V-reversal (3-bar drop + reclaim)", trades)

# â•â•â• PATTERN 17: Time-filtered â€” Only trade 9:30-11:00 (high vol hours) â•â•â•
print("\n--- TIME-FILTERED: VWAP reversion 9:30-11:00 only ---")
print("-" * 95)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        cum_pv = cum_v = 0
        for i, c in enumerate(dc):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 15: continue
            
            hour = dc[i]['ts'].hour
            minute = dc[i]['ts'].minute
            # Only 9:30-11:00
            if hour < 9 or (hour == 9 and minute < 30) or hour > 11: continue
            
            vwap = cum_pv / cum_v
            dev = (c['close'] - vwap) / vwap * 100
            
            if dev < -1.0:
                entry = c['close']
                sl = entry * 0.995
                target = vwap
                for j in range(i+1, min(i+20, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); break
                break
eval("VWAP reversion 1% (9:30-11:00 only)", trades)

# â•â•â• PATTERN 18: EMA(20) bounce in uptrend â•â•â•
print("\n--- EMA(20) BOUNCE ---")
print("-" * 95)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 120: continue
        closes = [c['close'] for c in dc]
        entered = False
        for i in range(30, len(dc)):
            if entered: break
            ema20 = ema(closes[:i+1], 20)
            ema50 = ema(closes[:i+1], 50)
            
            # Uptrend: EMA20 > EMA50
            if ema20 <= ema50: continue
            
            # Price touches EMA20 from above (low within 0.2% of EMA20)
            c = dc[i]
            dist = abs(c['low'] - ema20) / ema20 * 100
            
            if dist < 0.2 and c['close'] > ema20:
                entry = c['close']
                sl = entry * 0.995
                target = entry * 1.005
                for j in range(i+1, min(i+20, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
            entered = False
eval("EMA20 bounce in uptrend", trades)

# â•â•â• PATTERN 19: VWAP reclaim from below with volume â•â•â•
print("\n--- VWAP RECLAIM + VOLUME SURGE ---")
print("-" * 95)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, dc in sorted(days.items()):
        if len(dc) < 60: continue
        cum_pv = cum_v = 0
        entered = False
        for i, c in enumerate(dc):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 20:
                entered = False; continue
            
            vwap = cum_pv / cum_v
            avg_vol = sum(cc['volume'] for cc in dc[max(0,i-20):i]) / min(20, i) if i > 0 else 0
            
            # Below VWAP, then reclaim with volume
            below_prev = i > 0 and dc[i-1]['close'] < (cum_pv - dc[i-1]['close']*dc[i-1]['volume']) / max(1, cum_v - dc[i-1]['volume'])
            above_now = c['close'] > vwap
            vol_surge = c['volume'] > avg_vol * 1.5 if avg_vol > 0 else False
            
            if below_prev and above_now and vol_surge and not entered:
                entry = c['close']
                sl = entry * 0.995
                target = entry * 1.005
                for j in range(i+1, min(i+20, len(dc))):
                    if dc[j]['low'] <= sl:
                        trades.append({'pnl': (sl-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
                    elif dc[j]['high'] >= target:
                        trades.append({'pnl': (target-entry)/entry*CAPITAL-BROKERAGE}); entered=True; break
            entered = False
eval("VWAP reclaim + 1.5x volume", trades)

conn.close()

