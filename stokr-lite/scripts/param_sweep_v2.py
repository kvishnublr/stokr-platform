#!/usr/bin/env python3
"""Parametric sweep with trailing stops (matching Java backtest logic)."""
import subprocess, os, sys

def get_candles():
    result = subprocess.run(
        ['psql', '-h', 'localhost', '-p', '5432', '-U', 'postgres', '-d', 'stokr_lite',
         '-t', '-A', '-F', '|', '-c',
         "SELECT symbol, timestamp, open, high, low, close, volume FROM candle_data WHERE timeframe='daily' ORDER BY symbol, timestamp"],
        capture_output=True, text=True, env={**os.environ, 'PGPASSWORD': '`$POSTGRES_PASSWORD'}
    )
    candles = {}
    for line in result.stdout.strip().split('\n'):
        parts = line.split('|')
        if len(parts) != 7: continue
        sym, ts, o, h, l, c, v = parts
        candles.setdefault(sym, []).append({
            'timestamp': ts, 'open': float(o), 'high': float(h),
            'low': float(l), 'close': float(c), 'volume': int(v)
        })
    return candles

def compute_rsi(closes, period=14):
    rsi = [50.0] * len(closes)
    if len(closes) < period + 1: return rsi
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

def compute_ema(closes, period):
    ema = [0.0] * len(closes)
    if len(closes) < period: return ema
    k = 2.0 / (period + 1)
    ema[period-1] = sum(closes[:period]) / period
    for i in range(period, len(closes)):
        ema[i] = closes[i] * k + ema[i-1] * (1 - k)
    return ema

BROKERAGE = 80

def backtest_with_trail(symbols_data, entry_fn, sl_pct, target_fn, max_hold, trail_trigger, trail_dist):
    """
    Generic backtest with trailing stop logic matching Java BacktestController.
    entry_fn(candle, i, closes, ema50, rsi14) -> entry price or None
    target_fn(candle, i, closes, ema50) -> target price
    """
    trades = []
    for sym, cndls in symbols_data.items():
        n = len(cndls)
        if n < 60: continue
        closes = [c['close'] for c in cndls]
        ema50 = compute_ema(closes, 50)
        ema20 = compute_ema(closes, 20)
        rsi14 = compute_rsi(closes, 14)
        for i in range(55, n - max_hold - 1):
            entry_price = entry_fn(cndls[i], i, closes, ema50, rsi14)
            if entry_price is None: continue
            target_price = target_fn(cndls[i], i, closes, ema50)
            if target_price is None or target_price <= entry_price: continue
            
            sl_price = entry_price * (1 - sl_pct)
            current_sl = sl_price
            best_price = entry_price
            trail_activated = False
            target_locked = False
            exit_price = None
            exit_type = None
            
            for j in range(i + 1, min(i + 1 + max_hold, n)):
                hi = cndls[j]['high']
                lo = cndls[j]['low']
                
                if hi > best_price:
                    best_price = hi
                    gain = (best_price - entry_price) / entry_price * 100
                    if gain >= trail_trigger:
                        trail_activated = True
                
                if not target_locked and hi >= target_price:
                    target_locked = True
                    trail_activated = True
                    if target_price > current_sl:
                        current_sl = target_price
                
                if trail_activated:
                    new_trail = best_price * (1 - trail_dist / 100)
                    if new_trail > current_sl:
                        current_sl = new_trail
                
                if lo <= current_sl:
                    exit_price = current_sl
                    exit_type = "TARGET_HIT" if target_locked else ("TRAIL_SL" if trail_activated else "SL_HIT")
                    break
            
            if exit_price is None:
                exit_price = closes[min(i + max_hold, n - 1)]
                exit_type = "MAX_HOLD"
            
            pnl = exit_price - entry_price - BROKERAGE
            trades.append({'symbol': sym, 'pnl': pnl, 'exit': exit_type})
    return trades

if __name__ == '__main__':
    data = get_candles()
    print(f"Loaded {sum(len(v) for v in data.values())} candles, {len(data)} symbols\n")

    # EMA50_DISTANCE sweep with trailing stops
    print("=" * 85)
    print("EMA50_DISTANCE PARAMETER SWEEP (with trailing stops)")
    print("=" * 85)
    print(f"{'DIST%':>6} {'SL%':>5} {'HOLD':>5} {'TRAIL':>6} {'TDIST':>6} {'TRADES':>7} {'WR%':>6} {'NET':>10} {'PF':>6}")
    print("-" * 70)
    for dist in [-4, -5, -6, -7]:
        for sl in [0.03, 0.04, 0.05]:
            for hold in [5, 7, 10]:
                for tt, td in [(0.5, 0.25), (0.5, 0.3), (1.0, 0.3), (1.0, 0.5), (2.0, 0.5)]:
                    def entry_fn(c, i, cl, e50, rsi):
                        if c['volume'] <= 0 or cl[i] < 50: return None
                        dist_pct = (cl[i] - e50[i]) / e50[i] * 100
                        if dist_pct > dist or dist_pct < -15: return None
                        if cl[i] >= c['open']: return None
                        return cl[i]
                    def target_fn(c, i, cl, e50):
                        return e50[i]
                    trades = backtest_with_trail(data, entry_fn, sl, target_fn, hold, tt, td)
                    if trades:
                        wins = sum(1 for t in trades if t['pnl'] > 0)
                        net = sum(t['pnl'] for t in trades)
                        gw = sum(t['pnl'] for t in trades if t['pnl'] > 0)
                        gl = abs(sum(t['pnl'] for t in trades if t['pnl'] <= 0))
                        pf = gw / gl if gl > 0 else 999
                        wr = wins / len(trades) * 100
                        marker = " ***" if net > 100000 else ""
                        exits = {}
                        for t in trades:
                            exits[t['exit']] = exits.get(t['exit'], 0) + 1
                        exit_str = ' '.join(f"{k}:{v}" for k, v in sorted(exits.items()))
                        print(f"{dist:>6} {sl:>5.2f} {hold:>5} {tt:>6.1f} {td:>6.2f} {len(trades):>7} {wr:>6.1f} â‚¹{net:>9,.0f} {pf:>6.2f}{marker}")

    # RSI_OVERSOLD sweep with trailing stops
    print("\n" + "=" * 85)
    print("RSI_OVERSOLD PARAMETER SWEEP (with trailing stops)")
    print("=" * 85)
    print(f"{'RSI':>5} {'SL%':>5} {'TGT%':>5} {'HOLD':>5} {'TRAIL':>6} {'TDIST':>6} {'TRADES':>7} {'WR%':>6} {'NET':>10} {'PF':>6}")
    print("-" * 75)
    for rsi_thresh in [30, 33, 35, 38, 40]:
        for sl in [0.02, 0.03, 0.04]:
            for tgt_pct in [0.02, 0.03, 0.04, 0.05]:
                for hold in [3, 5, 7]:
                    for tt, td in [(0.5, 0.25), (1.0, 0.3), (2.0, 0.5)]:
                        def entry_fn(c, i, cl, e50, rsi):
                            if c['volume'] <= 0 or cl[i] < 50: return None
                            if rsi[i] >= rsi_thresh: return None
                            return cl[i]
                        def target_fn(c, i, cl, e50):
                            return cl[i] * (1 + tgt_pct)
                        trades = backtest_with_trail(data, entry_fn, sl, target_fn, hold, tt, td)
                        if trades:
                            wins = sum(1 for t in trades if t['pnl'] > 0)
                            net = sum(t['pnl'] for t in trades)
                            gw = sum(t['pnl'] for t in trades if t['pnl'] > 0)
                            gl = abs(sum(t['pnl'] for t in trades if t['pnl'] <= 0))
                            pf = gw / gl if gl > 0 else 999
                            wr = wins / len(trades) * 100
                            marker = " ***" if net > 100000 else ""
                            print(f"{rsi_thresh:>5} {sl:>5.2f} {tgt_pct:>5.2f} {hold:>5} {tt:>6.1f} {td:>6.2f} {len(trades):>7} {wr:>6.1f} â‚¹{net:>9,.0f} {pf:>6.2f}{marker}")

