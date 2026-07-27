import psycopg2
import json
from collections import defaultdict
from datetime import datetime, timedelta

conn = psycopg2.connect(host='localhost', dbname='stokr_lite', user='postgres', password='`$POSTGRES_PASSWORD')
cur = conn.cursor()

# Load NIFTY_50 symbols that have 1-min data
cur.execute("""
    SELECT symbol, count(*) as cnt FROM candle_data 
    WHERE timeframe = '1min' 
    GROUP BY symbol HAVING count(*) > 5000
    ORDER BY cnt DESC LIMIT 30
""")
symbols = [r[0] for r in cur.fetchall()]
print(f"Loaded {len(symbols)} symbols with sufficient 1-min data")

# Load all 1-min data for these symbols
print("Loading 1-min candles...")
cur.execute("""
    SELECT symbol, timestamp, open, high, low, close, volume
    FROM candle_data
    WHERE timeframe = '1min' AND symbol IN %s
    ORDER BY symbol, timestamp
""", (tuple(symbols),))

data = defaultdict(list)
for row in cur.fetchall():
    sym, ts, o, h, l, c, v = row
    data[sym].append({
        'ts': ts, 'open': float(o), 'high': float(h), 
        'low': float(l), 'close': float(c), 'volume': int(v or 0)
    })

total = sum(len(v) for v in data.values())
print(f"Loaded {total} candles across {len(data)} symbols")

# Group candles by trading day
def get_day_candles(candles):
    days = defaultdict(list)
    for c in candles:
        d = c['ts'].date()
        days[d].append(c)
    return days

BROKERAGE = 80  # â‚¹80 per round trip
CAPITAL = 100000

print("\n" + "=" * 90)
print("INTRADAY PATTERN SCAN â€” Looking for statistical edge after brokerage")
print("=" * 90)

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# PATTERN 1: VWAP Mean Reversion â€” Buy when price >1% below VWAP, sell at VWAP
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
print("\n1. VWAP MEAN REVERSION (buy 1% below VWAP, target VWAP)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue  # need enough data
        
        cum_pv = 0
        cum_v = 0
        for i, c in enumerate(day_candles):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            
            if cum_v == 0 or i < 15: continue  # skip first 15 min
            
            vwap = cum_pv / cum_v
            price = c['close']
            deviation = (price - vwap) / vwap * 100
            
            # Entry: price >1% below VWAP (LONG)
            if deviation < -1.0:
                entry = price
                sl = entry * 0.995  # 0.5% SL
                target = vwap  # target VWAP
                
                # Simulate forward 30 candles max
                for j in range(i+1, min(i+30, len(day_candles))):
                    hc = day_candles[j]['high']
                    lc = day_candles[j]['low']
                    
                    if lc <= sl:
                        pnl = (sl - entry) / entry * CAPITAL - BROKERAGE
                        trades.append({'pnl': pnl, 'type': 'SL', 'hold': j-i})
                        break
                    elif hc >= target:
                        pnl = (target - entry) / entry * CAPITAL - BROKERAGE
                        trades.append({'pnl': pnl, 'type': 'WIN', 'hold': j-i})
                        break
                break  # one trade per day per symbol

wins = [t for t in trades if t['pnl'] > 0]
losses = [t for t in trades if t['pnl'] <= 0]
total_pnl = sum(t['pnl'] for t in trades)
print(f"  Trades: {len(trades)} | Wins: {len(wins)} | Losses: {len(losses)} | WR: {len(wins)/len(trades)*100 if trades else 0:.1f}%")
print(f"  Net PnL: â‚¹{total_pnl:,.2f} | Avg: â‚¹{total_pnl/len(trades) if trades else 0:,.2f}/trade")
print(f"  Avg Win: â‚¹{sum(t['pnl'] for t in wins)/len(wins) if wins else 0:,.2f} | Avg Loss: â‚¹{sum(t['pnl'] for t in losses)/len(losses) if losses else 0:,.2f}")
print(f"  PF: {sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999:.2f}")

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# PATTERN 2: Opening Range Breakout Reversal â€” First 15-min high/low breakout fails
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
print("\n2. ORB BREAKOUT FADE (fade first 15-min breakout)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        
        # First 15 candles = opening range
        or_candles = day_candles[:15]
        or_high = max(c['high'] for c in or_candles)
        or_low = min(c['low'] for c in or_candles)
        or_range = or_high - or_low
        
        if or_range <= 0: continue
        
        # Look for breakout + fade in candles 16-45
        entered = False
        for i in range(15, min(45, len(day_candles))):
            c = day_candles[i]
            
            # Breakout above OR high â†’ fade (SHORT)
            if c['close'] > or_high and not entered:
                entry = c['close']
                sl = entry * 1.005  # 0.5% above entry
                target = or_low  # target bottom of range
                
                for j in range(i+1, min(i+30, len(day_candles))):
                    hc = day_candles[j]['high']
                    lc = day_candles[j]['low']
                    if hc >= sl:
                        pnl = (entry - sl) / entry * CAPITAL - BROKERAGE
                        trades.append({'pnl': pnl, 'type': 'SL'})
                        entered = True
                        break
                    elif lc <= target:
                        pnl = (entry - target) / entry * CAPITAL - BROKERAGE
                        trades.append({'pnl': pnl, 'type': 'WIN'})
                        entered = True
                        break
                break
            # Breakdown below OR low â†’ fade (LONG)
            elif c['close'] < or_low and not entered:
                entry = c['close']
                sl = entry * 0.995
                target = or_high
                
                for j in range(i+1, min(i+30, len(day_candles))):
                    hc = day_candles[j]['high']
                    lc = day_candles[j]['low']
                    if lc <= sl:
                        pnl = (sl - entry) / entry * CAPITAL - BROKERAGE
                        trades.append({'pnl': pnl, 'type': 'SL'})
                        entered = True
                        break
                    elif hc >= target:
                        pnl = (target - entry) / entry * CAPITAL - BROKERAGE
                        trades.append({'pnl': pnl, 'type': 'WIN'})
                        entered = True
                        break
                break

wins = [t for t in trades if t['pnl'] > 0]
losses = [t for t in trades if t['pnl'] <= 0]
total_pnl = sum(t['pnl'] for t in trades)
print(f"  Trades: {len(trades)} | Wins: {len(wins)} | Losses: {len(losses)} | WR: {len(wins)/len(trades)*100 if trades else 0:.1f}%")
print(f"  Net PnL: â‚¹{total_pnl:,.2f} | Avg: â‚¹{total_pnl/len(trades) if trades else 0:,.2f}/trade")
print(f"  Avg Win: â‚¹{sum(t['pnl'] for t in wins)/len(wins) if wins else 0:,.2f} | Avg Loss: â‚¹{sum(t['pnl'] for t in losses)/len(losses) if losses else 0:,.2f}")
print(f"  PF: {sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999:.2f}")

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# PATTERN 3: RSI Extreme Fade â€” RSI(14) on 5-min <20 buy, >80 sell
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
print("\n3. RSI EXTREME FADE (5-min RSI<20 buy, RSI>80 sell)")
print("-" * 70)

def compute_rsi(closes, period=14):
    if len(closes) < period + 1: return None
    gains = []
    losses = []
    for i in range(1, len(closes)):
        d = closes[i] - closes[i-1]
        gains.append(max(d, 0))
        losses.append(max(-d, 0))
    avg_gain = sum(gains[-period:]) / period
    avg_loss = sum(losses[-period:]) / period
    if avg_loss == 0: return 100
    rs = avg_gain / avg_loss
    return 100 - 100 / (1 + rs)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 120: continue
        
        # Build 5-min candles from 1-min
        five_min = []
        for i in range(0, len(day_candles) - 4, 5):
            chunk = day_candles[i:i+5]
            five_min.append({
                'open': chunk[0]['open'],
                'high': max(c['high'] for c in chunk),
                'low': min(c['low'] for c in chunk),
                'close': chunk[-1]['close'],
                'ts': chunk[0]['ts']
            })
        
        if len(five_min) < 30: continue
        
        # Compute RSI on 5-min
        closes = [c['close'] for c in five_min]
        entered = False
        for i in range(20, len(five_min)):
            rsi = compute_rsi(closes[:i+1], 14)
            if rsi is None: continue
            
            if rsi < 25 and not entered:  # oversold â†’ BUY
                entry = five_min[i]['close']
                sl = entry * 0.995  # 0.5% SL
                target = entry * 1.005  # 0.5% target (quick reversion)
                
                for j in range(i+1, min(i+6, len(five_min))):  # max 30 min hold
                    lc = five_min[j]['low']
                    hc = five_min[j]['high']
                    if lc <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE, 'type': 'SL'})
                        entered = True
                        break
                    elif hc >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE, 'type': 'WIN'})
                        entered = True
                        break
            elif rsi > 75 and not entered:  # overbought â†’ SELL
                entry = five_min[i]['close']
                sl = entry * 1.005
                target = entry * 0.995
                
                for j in range(i+1, min(i+6, len(five_min))):
                    lc = five_min[j]['low']
                    hc = five_min[j]['high']
                    if hc >= sl:
                        trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE, 'type': 'SL'})
                        entered = True
                        break
                    elif lc <= target:
                        trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE, 'type': 'WIN'})
                        entered = True
                        break
            entered = False  # reset for next day

wins = [t for t in trades if t['pnl'] > 0]
losses = [t for t in trades if t['pnl'] <= 0]
total_pnl = sum(t['pnl'] for t in trades)
print(f"  Trades: {len(trades)} | Wins: {len(wins)} | Losses: {len(losses)} | WR: {len(wins)/len(trades)*100 if trades else 0:.1f}%")
print(f"  Net PnL: â‚¹{total_pnl:,.2f} | Avg: â‚¹{total_pnl/len(trades) if trades else 0:,.2f}/trade")
if wins: print(f"  Avg Win: â‚¹{sum(t['pnl'] for t in wins)/len(wins):,.2f}")
if losses: print(f"  Avg Loss: â‚¹{sum(t['pnl'] for t in losses)/len(losses):,.2f}")
if losses: print(f"  PF: {sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)):.2f}")

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# PATTERN 4: Opening Gap Fade â€” Stock gaps up/down at open, fades intraday
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
print("\n4. OPENING GAP FADE (gap >0.5%, fade back to previous close)")
print("-" * 70)

# Need daily data for previous close
cur.execute("""
    SELECT symbol, timestamp::date, close FROM candle_data
    WHERE timeframe = 'daily' AND symbol IN %s
    ORDER BY symbol, timestamp
""", (tuple(symbols),))
prev_close = {}
for sym, day, close in cur.fetchall():
    if sym not in prev_close:
        prev_close[sym] = {}
    prev_close[sym][day] = float(close)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        if day not in prev_close.get(sym, {}): continue
        
        pc = prev_close[sym][day]
        open_price = day_candles[0]['open']
        gap_pct = (open_price - pc) / pc * 100
        
        # Fade gap up (gap > 0.5% â†’ SHORT at open)
        if gap_pct > 0.5:
            entry = open_price
            sl = entry * 1.005  # 0.5% SL
            target = pc  # target previous close
            
            for j in range(1, min(60, len(day_candles))):
                hc = day_candles[j]['high']
                lc = day_candles[j]['low']
                if hc >= sl:
                    trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE, 'type': 'SL', 'gap': gap_pct})
                    break
                elif lc <= target:
                    trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE, 'type': 'WIN', 'gap': gap_pct})
                    break
        
        # Fade gap down (gap <-0.5% â†’ BUY at open)
        elif gap_pct < -0.5:
            entry = open_price
            sl = entry * 0.995
            target = pc
            
            for j in range(1, min(60, len(day_candles))):
                hc = day_candles[j]['high']
                lc = day_candles[j]['low']
                if lc <= sl:
                    trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE, 'type': 'SL', 'gap': gap_pct})
                    break
                elif hc >= target:
                    trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE, 'type': 'WIN', 'gap': gap_pct})
                    break

wins = [t for t in trades if t['pnl'] > 0]
losses = [t for t in trades if t['pnl'] <= 0]
total_pnl = sum(t['pnl'] for t in trades)
print(f"  Trades: {len(trades)} | Wins: {len(wins)} | Losses: {len(losses)} | WR: {len(wins)/len(trades)*100 if trades else 0:.1f}%")
print(f"  Net PnL: â‚¹{total_pnl:,.2f} | Avg: â‚¹{total_pnl/len(trades) if trades else 0:,.2f}/trade")
if wins: print(f"  Avg Win: â‚¹{sum(t['pnl'] for t in wins)/len(wins):,.2f}")
if losses: print(f"  Avg Loss: â‚¹{sum(t['pnl'] for t in losses)/len(losses):,.2f}")
if losses: print(f"  PF: {sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)):.2f}")

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# PATTERN 5: VWAP Bounce â€” Price crosses VWAP from below, buy
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
print("\n5. VWAP CROSS BOUNCE (price crosses above VWAP, buy)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        
        cum_pv = 0
        cum_v = 0
        prev_above = None
        entered = False
        
        for i, c in enumerate(day_candles):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            
            if cum_v == 0 or i < 15: 
                prev_above = None
                continue
            
            vwap = cum_pv / cum_v
            above = c['close'] > vwap
            
            # Cross above VWAP from below
            if prev_above == False and above and not entered:
                entry = c['close']
                sl = entry * 0.995  # 0.5% SL
                target = entry * 1.005  # 0.5% target
                
                for j in range(i+1, min(i+20, len(day_candles))):
                    lc = day_candles[j]['low']
                    hc = day_candles[j]['high']
                    if lc <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE, 'type': 'SL'})
                        entered = True
                        break
                    elif hc >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE, 'type': 'WIN'})
                        entered = True
                        break
                if entered: break
            
            prev_above = above

wins = [t for t in trades if t['pnl'] > 0]
losses = [t for t in trades if t['pnl'] <= 0]
total_pnl = sum(t['pnl'] for t in trades)
print(f"  Trades: {len(trades)} | Wins: {len(wins)} | Losses: {len(losses)} | WR: {len(wins)/len(trades)*100 if trades else 0:.1f}%")
print(f"  Net PnL: â‚¹{total_pnl:,.2f} | Avg: â‚¹{total_pnl/len(trades) if trades else 0:,.2f}/trade")
if wins: print(f"  Avg Win: â‚¹{sum(t['pnl'] for t in wins)/len(wins):,.2f}")
if losses: print(f"  Avg Loss: â‚¹{sum(t['pnl'] for t in losses)/len(losses):,.2f}")
if losses and wins: print(f"  PF: {sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)):.2f}")

conn.close()

