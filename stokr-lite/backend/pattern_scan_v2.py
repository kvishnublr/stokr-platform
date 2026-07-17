import psycopg2
from collections import defaultdict

conn = psycopg2.connect(host='localhost', dbname='stokr_lite', user='postgres', password='stokr2026')
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
    FROM candle_data
    WHERE timeframe = '1min' AND symbol IN %s
    ORDER BY symbol, timestamp
""", (tuple(symbols),))

data = defaultdict(list)
for row in cur.fetchall():
    sym, ts, o, h, l, c, v = row
    data[sym].append({'ts': ts, 'open': float(o), 'high': float(h), 'low': float(l), 'close': float(c), 'volume': int(v or 0)})

def get_day_candles(candles):
    days = defaultdict(list)
    for c in candles:
        days[c['ts'].date()].append(c)
    return days

def compute_rsi(closes, period=14):
    if len(closes) < period + 1: return None
    gains, losses_l = [], []
    for i in range(1, len(closes)):
        d = closes[i] - closes[i-1]
        gains.append(max(d, 0))
        losses_l.append(max(-d, 0))
    avg_gain = sum(gains[-period:]) / period
    avg_loss = sum(losses_l[-period:]) / period
    if avg_loss == 0: return 100
    return 100 - 100 / (1 + avg_gain / avg_loss)

BROKERAGE = 80
CAPITAL = 100000

print("=" * 90)
print("INTRADAY PATTERN SCAN v2 — Wider targets, more patterns")
print("=" * 90)

def evaluate(name, trades):
    if not trades:
        print(f"  No trades")
        return
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    total_pnl = sum(t['pnl'] for t in trades)
    wr = len(wins)/len(trades)*100
    avg_win = sum(t['pnl'] for t in wins)/len(wins) if wins else 0
    avg_loss = sum(t['pnl'] for t in losses)/len(losses) if losses else 0
    pf = sum(t['pnl'] for t in wins)/abs(sum(t['pnl'] for t in losses)) if losses else 999
    print(f"  Trades: {len(trades)} | Wins: {len(wins)} | WR: {wr:.1f}% | Net: ₹{total_pnl:,.0f} | Avg: ₹{total_pnl/len(trades):,.0f} | PF: {pf:.2f}")
    print(f"  Avg Win: ₹{avg_win:,.0f} | Avg Loss: ₹{avg_loss:,.0f}")

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 6: VWAP Extreme Reversion (wider — 1.5% deviation, target VWAP)
# ══════════════════════════════════════════════════════════════════════════════
print("\n6. VWAP EXTREME REVERSION (1.5% below VWAP, target VWAP, 0.75% SL)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        cum_pv = cum_v = 0
        for i, c in enumerate(day_candles):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 15: continue
            vwap = cum_pv / cum_v
            dev = (c['close'] - vwap) / vwap * 100
            if dev < -1.5:
                entry = c['close']
                sl = entry * 0.9925
                target = vwap
                for j in range(i+1, min(i+30, len(day_candles))):
                    if day_candles[j]['low'] <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE})
                        break
                    elif day_candles[j]['high'] >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE})
                        break
                break
evaluate("VWAP Extreme", trades)

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 7: VWAP Extreme SHORT (1.5% above VWAP, target VWAP)
# ══════════════════════════════════════════════════════════════════════════════
print("\n7. VWAP EXTREME SHORT (1.5% above VWAP, target VWAP, 0.75% SL)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        cum_pv = cum_v = 0
        for i, c in enumerate(day_candles):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 15: continue
            vwap = cum_pv / cum_v
            dev = (c['close'] - vwap) / vwap * 100
            if dev > 1.5:
                entry = c['close']
                sl = entry * 1.0075
                target = vwap
                for j in range(i+1, min(i+30, len(day_candles))):
                    if day_candles[j]['high'] >= sl:
                        trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE})
                        break
                    elif day_candles[j]['low'] <= target:
                        trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE})
                        break
                break
evaluate("VWAP Extreme SHORT", trades)

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 8: Volume Spike Reversal — 3x avg volume candle, fade direction
# ══════════════════════════════════════════════════════════════════════════════
print("\n8. VOLUME SPIKE FADE (3x avg vol candle, fade direction)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 30: continue
        volumes = [c['volume'] for c in day_candles]
        entered = False
        for i in range(20, len(day_candles)):
            avg_vol = sum(volumes[max(0,i-20):i]) / min(20, i)
            if avg_vol == 0: continue
            vol_ratio = day_candles[i]['volume'] / avg_vol
            if vol_ratio < 3: continue
            
            c = day_candles[i]
            is_bullish = c['close'] > c['open']
            
            if is_bullish and not entered:  # Big green candle → fade SHORT
                entry = c['close']
                sl = entry * 1.0075  # 0.75%
                target = entry * 0.9925  # 0.75%
                for j in range(i+1, min(i+20, len(day_candles))):
                    if day_candles[j]['high'] >= sl:
                        trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE})
                        entered = True; break
                    elif day_candles[j]['low'] <= target:
                        trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE})
                        entered = True; break
            elif not is_bullish and not entered:  # Big red candle → fade LONG
                entry = c['close']
                sl = entry * 0.9925
                target = entry * 1.0075
                for j in range(i+1, min(i+20, len(day_candles))):
                    if day_candles[j]['low'] <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE})
                        entered = True; break
                    elif day_candles[j]['high'] >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE})
                        entered = True; break
            entered = False
evaluate("Volume Spike Fade", trades)

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 9: VWAP Bounce with Volume Confirmation
# ══════════════════════════════════════════════════════════════════════════════
print("\n9. VWAP BOUNCE + VOLUME (cross VWAP with volume spike, target 0.5%)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        cum_pv = cum_v = 0
        prev_above = None
        for i, c in enumerate(day_candles):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 20:
                prev_above = None; continue
            vwap = cum_pv / cum_v
            above = c['close'] > vwap
            
            # Check volume spike at crossover
            avg_vol = sum(cc['volume'] for cc in day_candles[max(0,i-20):i]) / min(20, i) if i > 0 else 0
            vol_spike = c['volume'] > avg_vol * 1.5 if avg_vol > 0 else False
            
            if prev_above == False and above and vol_spike:
                entry = c['close']
                sl = entry * 0.995
                target = entry * 1.005
                for j in range(i+1, min(i+20, len(day_candles))):
                    if day_candles[j]['low'] <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE}); break
                    elif day_candles[j]['high'] >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE}); break
                break
            prev_above = above
evaluate("VWAP Cross + Volume", trades)

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 10: Morning Reversal (first 15 min direction → fade after 10:00)
# ══════════════════════════════════════════════════════════════════════════════
print("\n10. MORNING REVERSAL (fade first 15-min move after 10:00)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        first15 = day_candles[:15]
        first15_move = (first15[-1]['close'] - first15[0]['open']) / first15[0]['open'] * 100
        
        if abs(first15_move) < 0.3: continue  # need meaningful move
        
        # After 10:00 (candle 45), fade the morning direction
        entered = False
        for i in range(45, min(120, len(day_candles))):
            if entered: break
            c = day_candles[i]
            
            if first15_move > 0.3:  # Morning was UP → SHORT after 10:00
                entry = c['close']
                sl = entry * 1.0075
                target = entry * 0.9925  # 0.75% target
                for j in range(i+1, min(i+30, len(day_candles))):
                    if day_candles[j]['high'] >= sl:
                        trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE}); entered = True; break
                    elif day_candles[j]['low'] <= target:
                        trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE}); entered = True; break
            elif first15_move < -0.3:  # Morning was DOWN → LONG after 10:00
                entry = c['close']
                sl = entry * 0.9925
                target = entry * 1.0075
                for j in range(i+1, min(i+30, len(day_candles))):
                    if day_candles[j]['low'] <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE}); entered = True; break
                    elif day_candles[j]['high'] >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE}); entered = True; break
evaluate("Morning Reversal", trades)

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 11: RSI(5) Extreme + VWAP Confluence
# ══════════════════════════════════════════════════════════════════════════════
print("\n11. RSI(5) EXTREME + VWAP CONFLUENCE (RSI<10 + below VWAP → buy)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        cum_pv = cum_v = 0
        closes = [c['close'] for c in day_candles]
        entered = False
        
        for i, c in enumerate(day_candles):
            cum_pv += c['close'] * c['volume']
            cum_v += c['volume']
            if cum_v == 0 or i < 10: continue
            
            vwap = cum_pv / cum_v
            rsi5 = compute_rsi(closes[:i+1], 5)
            if rsi5 is None: continue
            
            # RSI(5) < 15 AND below VWAP → BUY
            if rsi5 < 15 and c['close'] < vwap * 0.995 and not entered:
                entry = c['close']
                sl = entry * 0.9925  # 0.75% SL
                target = entry * 1.0075  # 0.75% target
                for j in range(i+1, min(i+20, len(day_candles))):
                    if day_candles[j]['low'] <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE}); entered = True; break
                    elif day_candles[j]['high'] >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE}); entered = True; break
            # RSI(5) > 85 AND above VWAP → SHORT
            elif rsi5 > 85 and c['close'] > vwap * 1.005 and not entered:
                entry = c['close']
                sl = entry * 1.0075
                target = entry * 0.9925
                for j in range(i+1, min(i+20, len(day_candles))):
                    if day_candles[j]['high'] >= sl:
                        trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE}); entered = True; break
                    elif day_candles[j]['low'] <= target:
                        trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE}); entered = True; break
            entered = False
evaluate("RSI(5)+VWAP", trades)

# ══════════════════════════════════════════════════════════════════════════════
# PATTERN 12: 3-Candle Reversal (3 consecutive red after green run)
# ══════════════════════════════════════════════════════════════════════════════
print("\n12. 3-BAR REVERSAL (3 red candles → BUY at 4th, target 0.5%)")
print("-" * 70)

trades = []
for sym, candles in data.items():
    days = get_day_candles(candles)
    for day, day_candles in sorted(days.items()):
        if len(day_candles) < 60: continue
        entered = False
        for i in range(20, len(day_candles) - 4):
            if entered: break
            # 3 consecutive red candles
            if (day_candles[i-2]['close'] < day_candles[i-2]['open'] and
                day_candles[i-1]['close'] < day_candles[i-1]['open'] and
                day_candles[i]['close'] < day_candles[i]['open']):
                
                entry = day_candles[i+1]['open']  # buy at next candle open
                sl = entry * 0.995
                target = entry * 1.005
                
                for j in range(i+2, min(i+20, len(day_candles))):
                    if day_candles[j]['low'] <= sl:
                        trades.append({'pnl': (sl - entry) / entry * CAPITAL - BROKERAGE}); entered = True; break
                    elif day_candles[j]['high'] >= target:
                        trades.append({'pnl': (target - entry) / entry * CAPITAL - BROKERAGE}); entered = True; break
            # 3 consecutive green candles → SHORT
            elif (day_candles[i-2]['close'] > day_candles[i-2]['open'] and
                  day_candles[i-1]['close'] > day_candles[i-1]['open'] and
                  day_candles[i]['close'] > day_candles[i]['open']):
                
                entry = day_candles[i+1]['open']
                sl = entry * 1.005
                target = entry * 0.995
                
                for j in range(i+2, min(i+20, len(day_candles))):
                    if day_candles[j]['high'] >= sl:
                        trades.append({'pnl': (entry - sl) / entry * CAPITAL - BROKERAGE}); entered = True; break
                    elif day_candles[j]['low'] <= target:
                        trades.append({'pnl': (entry - target) / entry * CAPITAL - BROKERAGE}); entered = True; break
            entered = False
evaluate("3-Bar Reversal", trades)

conn.close()
