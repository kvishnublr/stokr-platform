#!/usr/bin/env python3
"""
Test OB-like mean reversion strategies on daily candles.
Strategies that exploit oversold/mean-reversion patterns similar to Oversold Bounce.
"""
import json, sys, os
sys.path.insert(0, '/opt/stokr/stokr-platform/backend/src/main/java')

import subprocess
import tempfile

# Query daily candles for NIFTY_50 symbols
QUERY = """
SELECT symbol, timestamp, open, high, low, close, volume
FROM candle_data
WHERE timeframe = 'daily'
AND timestamp >= '2025-07-01'
ORDER BY symbol, timestamp
"""

# We'll query the DB directly
import subprocess
result = subprocess.run(
    ['psql', '-h', 'localhost', '-p', '5432', '-U', 'postgres', '-d', 'stokr_lite',
     '-t', '-A', '-F', '|', '-c', QUERY],
    capture_output=True, text=True,
    env={**os.environ, 'PGPASSWORD': '`$POSTGRES_PASSWORD'}
)

# Parse candle data
symbols = {}
for line in result.stdout.strip().split('\n'):
    if not line.strip():
        continue
    parts = line.split('|')
    if len(parts) < 7:
        continue
    sym, ts, o, h, l, c, v = parts[0], parts[1], float(parts[2]), float(parts[3]), float(parts[4]), float(parts[5]), int(parts[6])
    if sym not in symbols:
        symbols[sym] = []
    symbols[sym].append({
        'ts': ts, 'open': o, 'high': h, 'low': l, 'close': c, 'volume': v
    })

print(f"Loaded {len(symbols)} symbols, {sum(len(v) for v in symbols.values())} daily candles")

# â”€â”€â”€ STRATEGY DEFINITIONS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

def compute_ema(closes, period):
    ema = [0.0] * len(closes)
    k = 2.0 / (period + 1)
    ema[0] = closes[0]
    for i in range(1, len(closes)):
        ema[i] = closes[i] * k + ema[i-1] * (1 - k)
    return ema

def compute_rsi(closes, period=14):
    rsi = [50.0] * len(closes)
    if len(closes) < period + 1:
        return rsi
    gains, losses = [], []
    for i in range(1, len(closes)):
        d = closes[i] - closes[i-1]
        gains.append(max(d, 0))
        losses.append(max(-d, 0))
    avg_g = sum(gains[:period]) / period
    avg_l = sum(losses[:period]) / period
    for i in range(period, len(gains)):
        avg_g = (avg_g * (period - 1) + gains[i]) / period
        avg_l = (avg_l * (period - 1) + losses[i]) / period
        rs = avg_g / avg_l if avg_l > 0 else 100
        rsi[i + 1] = 100 - 100 / (1 + rs)
    return rsi

def backtest(candles, strategy_fn, name, capital=100000, brokerage=80, hold_days=None):
    """Generic backtester: enter on signal, exit on SL/Target/Time-stop."""
    n = len(candles)
    if n < 60:
        return None

    closes = [c['close'] for c in candles]
    ema50 = compute_ema(closes, 50)
    ema20 = compute_ema(closes, 20)
    rsi14 = compute_rsi(closes, 14)

    trades = []
    i = 55  # start after warmup
    while i < n - 1:
        sig = strategy_fn(candles, i, ema50, ema20, rsi14)
        if sig:
            entry = candles[i+1]['open']  # buy next day open
            side = sig.get('side', 'BUY')
            sl = sig['sl']
            tgt = sig['target']
            max_hold = hold_days or sig.get('hold', 7)

            exit_price = entry
            exit_type = 'TIME'
            for j in range(i+2, min(i+2+max_hold, n)):
                if side == 'BUY':
                    if candles[j]['low'] <= sl:
                        exit_price = sl
                        exit_type = 'SL'
                        break
                    if candles[j]['high'] >= tgt:
                        exit_price = tgt
                        exit_type = 'TARGET'
                        break
                else:
                    if candles[j]['high'] >= sl:
                        exit_price = sl
                        exit_type = 'SL'
                        break
                    if candles[j]['low'] <= tgt:
                        exit_price = tgt
                        exit_type = 'TARGET'
                        break
                exit_price = candles[j]['close']
            else:
                exit_price = candles[min(i+1+max_hold, n-1)]['close']

            qty = int(capital * 0.95 / entry)
            if qty <= 0:
                i += 1
                continue
            pnl = (exit_price - entry) * qty if side == 'BUY' else (entry - exit_price) * qty
            net = pnl - brokerage * 2
            trades.append({
                'symbol': candles[i]['symbol'] if 'symbol' in candles[i] else '?',
                'entry': round(entry, 2),
                'exit': round(exit_price, 2),
                'side': side,
                'pnl': round(net, 2),
                'exit_type': exit_type,
                'hold': j - i - 1 if 'j' in dir() else max_hold,
            })
            i = i + 2 + max_hold
        else:
            i += 1

    if not trades:
        return {'name': name, 'trades': 0}

    wins = [t for t in trades if t['pnl'] > 0]
    losses_list = [t for t in trades if t['pnl'] <= 0]
    total_pnl = sum(t['pnl'] for t in trades)
    win_rate = len(wins) / len(trades) * 100
    avg_win = sum(t['pnl'] for t in wins) / len(wins) if wins else 0
    avg_loss = sum(t['pnl'] for t in losses_list) / len(losses_list) if losses_list else 0
    pf = sum(t['pnl'] for t in wins) / abs(sum(t['pnl'] for t in losses_list)) if losses_list and sum(t['pnl'] for t in losses_list) != 0 else 999

    return {
        'name': name,
        'trades': len(trades),
        'wins': len(wins),
        'losses': len(losses_list),
        'win_rate': round(win_rate, 1),
        'total_pnl': round(total_pnl, 2),
        'avg_pnl': round(total_pnl / len(trades), 2),
        'profit_factor': round(pf, 2),
        'avg_win': round(avg_win, 2),
        'avg_loss': round(avg_loss, 2),
    }


# â”€â”€â”€ STRATEGY 1: OB Original (baseline) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def ob_original(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    pc = candles[i-1]['close']
    if c < 50 or pc <= 0: return None
    drop = (c - pc) / pc * 100
    if drop > -3.0: return None
    if candles[i]['volume'] <= 0 or candles[i-1]['volume'] <= 0: return None
    if drop <= -4.9: return None
    dist = (c - ema50[i]) / ema50[i] * 100
    if dist < -15: return None
    return {'side': 'BUY', 'sl': c * 0.97, 'target': c * 1.015, 'hold': 7}

# â”€â”€â”€ STRATEGY 2: RSI Oversold (<30) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def rsi_oversold(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    if c < 50: return None
    if rsi14[i] >= 30: return None
    if rsi14[i-1] >= 30: return None  # sustained oversold
    return {'side': 'BUY', 'sl': c * 0.97, 'target': c * 1.02, 'hold': 5}

# â”€â”€â”€ STRATEGY 3: 2-Day Drop (>2% total) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def two_day_drop(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    pc2 = candles[i-2]['close']
    if c < 50 or pc2 <= 0: return None
    drop = (c - pc2) / pc2 * 100
    if drop > -2.0: return None
    if candles[i]['close'] > candles[i]['open']: return None  # today must be red
    if candles[i-1]['close'] > candles[i-1]['open']: return None  # yesterday red too
    return {'side': 'BUY', 'sl': c * 0.96, 'target': c * 1.02, 'hold': 5}

# â”€â”€â”€ STRATEGY 4: Distance from 50 EMA (>5% below) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def ema50_distance(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    if c < 50: return None
    dist = (c - ema50[i]) / ema50[i] * 100
    if dist > -5.0: return None
    if dist < -15: return None  # too far = structural breakdown
    return {'side': 'BUY', 'sl': c * 0.96, 'target': ema50[i], 'hold': 10}

# â”€â”€â”€ STRATEGY 5: Price crosses above 20 EMA from below â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def ema20_cross(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    pc = candles[i-1]['close']
    if c < 50: return None
    if pc >= ema20[i-1]: return None  # was below yesterday
    if c <= ema20[i]: return None  # not above today
    if c < ema50[i]: return None  # must be above 50 EMA too (uptrend)
    return {'side': 'BUY', 'sl': c * 0.97, 'target': c * 1.02, 'hold': 5}

# â”€â”€â”€ STRATEGY 6: Volume Climax + Reversal (high vol red â†’ green) â”€
def volume_climax(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    o = candles[i]['open']
    if c < 50: return None
    if c >= o: return None  # today red
    avg_vol = sum(candles[j]['volume'] for j in range(i-10, i)) / 10
    if avg_vol <= 0: return None
    if candles[i]['volume'] < avg_vol * 2: return None  # volume spike
    if candles[i+1 if i+1 < len(candles) else i]['close'] <= candles[i+1 if i+1 < len(candles) else i]['open']: return None
    # Actually: check if NEXT day is green (we enter at next day open)
    # We need next day to be green to confirm reversal
    return {'side': 'BUY', 'sl': c * 0.96, 'target': c * 1.02, 'hold': 5}

# â”€â”€â”€ STRATEGY 7: 3 Consecutive Red Days â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def three_red_days(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    if c < 50: return None
    for k in range(3):
        if candles[i-k]['close'] > candles[i-k]['open']: return None
    total_drop = (c - candles[i-3]['close']) / candles[i-3]['close'] * 100
    if total_drop > -3.0: return None  # need meaningful drop
    if total_drop < -10: return None  # too much = structural
    return {'side': 'BUY', 'sl': c * 0.96, 'target': c * 1.02, 'hold': 5}

# â”€â”€â”€ STRATEGY 8: Bollinger Band Oversold â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def bollinger_oversold(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    if c < 50: return None
    # 20-period BB
    closes_20 = [candles[j]['close'] for j in range(i-19, i+1)]
    mean = sum(closes_20) / 20
    std = (sum((x - mean)**2 for x in closes_20) / 20) ** 0.5
    lower_bb = mean - 2 * std
    if c > lower_bb: return None
    if candles[i-1]['close'] > (sum(candles[j]['close'] for j in range(i-20, i)) / 20 - 2 * (sum((x - sum(candles[j]['close'] for j in range(i-20, i))/20)**2 for x in [candles[j]['close'] for j in range(i-20, i)]) / 20)**0.5):
        return None  # was NOT below BB yesterday (fresh touch)
    return {'side': 'BUY', 'sl': c * 0.97, 'target': mean, 'hold': 7}  # target = middle BB

# â”€â”€â”€ STRATEGY 9: OB + RSI combo (stricter OB) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
def ob_rsi_combo(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    pc = candles[i-1]['close']
    if c < 50 or pc <= 0: return None
    drop = (c - pc) / pc * 100
    if drop > -3.0: return None
    if drop <= -4.9: return None
    if rsi14[i] >= 35: return None  # stricter RSI
    dist = (c - ema50[i]) / ema50[i] * 100
    if dist < -10: return None
    if dist > 0: return None  # must be below EMA50
    return {'side': 'BUY', 'sl': c * 0.97, 'target': c * 1.015, 'hold': 7}

# â”€â”€â”€ STRATEGY 10: Mean Reversion to 20 EMA (buy dip to EMA20) â”€â”€â”€â”€
def ema20_dip(candles, i, ema50, ema20, rsi14):
    c = candles[i]['close']
    if c < 50: return None
    dist_ema20 = (c - ema20[i]) / ema20[i] * 100
    dist_ema50 = (c - ema50[i]) / ema50[i] * 100
    if dist_ema20 > -1.0: return None  # must be near/below EMA20
    if dist_ema20 < -5.0: return None  # too far
    if dist_ema50 < 0: return None  # must be above EMA50 (uptrend)
    if rsi14[i] >= 40: return None  # somewhat oversold
    return {'side': 'BUY', 'sl': c * 0.97, 'target': ema20[i], 'hold': 5}


# â”€â”€â”€ RUN ALL STRATEGIES â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
all_candles = []
for sym, cndl_list in sorted(symbols.items()):
    for c in cndl_list:
        c['symbol'] = sym
    all_candles.extend(cndl_list)

# Group by symbol for per-symbol backtesting
strategies = [
    ("OB Original (baseline)", ob_original, 7),
    ("RSI < 30 Oversold", rsi_oversold, 5),
    ("2-Day Drop >2%", two_day_drop, 5),
    ("EMA50 Distance >5% below", ema50_distance, 10),
    ("EMA20 Cross Up", ema20_cross, 5),
    ("3 Consecutive Red Days", three_red_days, 5),
    ("OB + RSI Combo (stricter)", ob_rsi_combo, 7),
    ("EMA20 Dip in Uptrend", ema20_dip, 5),
]

results = []
for name, fn, hold in strategies:
    # Run per-symbol then aggregate
    all_trades = []
    for sym, cndl_list in symbols.items():
        for c in cndl_list:
            c['symbol'] = sym
        r = backtest(cndl_list, fn, name, hold_days=hold)
        if r and r['trades'] > 0:
            all_trades.append(r)

    if all_trades:
        total_trades = sum(r['trades'] for r in all_trades)
        total_wins = sum(r['wins'] for r in all_trades)
        total_losses = sum(r['losses'] for r in all_trades)
        total_pnl = sum(r['total_pnl'] for r in all_trades)
        avg_pf = sum(r['profit_factor'] * r['trades'] for r in all_trades) / total_trades if total_trades else 0
        wr = total_wins / total_trades * 100 if total_trades else 0
        results.append({
            'name': name,
            'trades': total_trades,
            'win_rate': round(wr, 1),
            'total_pnl': round(total_pnl, 2),
            'profit_factor': round(avg_pf, 2),
            'symbols': len(all_trades),
        })
    else:
        results.append({'name': name, 'trades': 0})

# Sort by total_pnl
results.sort(key=lambda x: x.get('total_pnl', 0), reverse=True)

print("\n" + "="*80)
print(f"{'STRATEGY':<30} {'TRADES':>7} {'WR%':>6} {'NET PnL':>10} {'PF':>6} {'SYMBOLS':>8}")
print("="*80)
for r in results:
    if r['trades'] == 0:
        print(f"{r['name']:<30} {'0':>7}")
    else:
        print(f"{r['name']:<30} {r['trades']:>7} {r['win_rate']:>5.1f}% {r['total_pnl']:>10.2f} {r['profit_factor']:>6.2f} {r['symbols']:>8}")
print("="*80)

