#!/usr/bin/env python3
"""Parametric sweep for EMA50_DISTANCE and RSI_OVERSOLD strategies."""
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

def backtest_ema50(symbols_data, dist_thresh, sl_pct, max_hold):
    trades = []
    for sym, cndls in symbols_data.items():
        n = len(cndls)
        if n < 55: continue
        closes = [c['close'] for c in cndls]
        ema50 = compute_ema(closes, 50)
        ema20 = compute_ema(closes, 20)
        rsi14 = compute_rsi(closes, 14)
        for i in range(55, n - max_hold - 1):
            if cndls[i]['volume'] <= 0: continue
            if cndls[i]['close'] < 50: continue
            dist = (closes[i] - ema50[i]) / ema50[i] * 100
            if dist > dist_thresh: continue
            if dist < -15: continue
            if closes[i] >= cndls[i]['open']: continue  # red day
            entry = closes[i]
            sl = entry * (1 - sl_pct)
            target = ema50[i]
            if target <= entry: continue
            pnl = None
            for j in range(i+1, min(i+1+max_hold, n)):
                if cndls[j]['low'] <= sl:
                    pnl = sl - entry - BROKERAGE
                    break
                if cndls[j]['high'] >= target:
                    pnl = target - entry - BROKERAGE
                    break
            else:
                pnl = closes[min(i+max_hold, n-1)] - entry - BROKERAGE
            trades.append({'symbol': sym, 'pnl': pnl})
    return trades

def backtest_rsi(symbols_data, rsi_thresh, sl_pct, target_pct, max_hold):
    trades = []
    for sym, cndls in symbols_data.items():
        n = len(cndls)
        if n < 20: continue
        closes = [c['close'] for c in cndls]
        ema50 = compute_ema(closes, 50)
        rsi14 = compute_rsi(closes, 14)
        for i in range(20, n - max_hold - 1):
            if cndls[i]['volume'] <= 0: continue
            if closes[i] < 50: continue
            if rsi14[i] >= rsi_thresh: continue
            entry = closes[i]
            sl = entry * (1 - sl_pct)
            target = entry * (1 + target_pct) if target_pct > 0 else ema50[i]
            if target <= entry: continue
            pnl = None
            for j in range(i+1, min(i+1+max_hold, n)):
                if cndls[j]['low'] <= sl:
                    pnl = sl - entry - BROKERAGE
                    break
                if cndls[j]['high'] >= target:
                    pnl = target - entry - BROKERAGE
                    break
            else:
                pnl = closes[min(i+max_hold, n-1)] - entry - BROKERAGE
            trades.append({'symbol': sym, 'pnl': pnl})
    return trades

def summarize(name, trades):
    if not trades:
        return f"{name}: 0 trades"
    wins = sum(1 for t in trades if t['pnl'] > 0)
    net = sum(t['pnl'] for t in trades)
    gross_win = sum(t['pnl'] for t in trades if t['pnl'] > 0)
    gross_loss = abs(sum(t['pnl'] for t in trades if t['pnl'] <= 0))
    pf = gross_win / gross_loss if gross_loss > 0 else 999
    wr = wins / len(trades) * 100
    return f"{name}: {len(trades)} trades, {wr:.1f}% WR, net=â‚¹{net:,.0f}, PF={pf:.2f}"

if __name__ == '__main__':
    data = get_candles()
    print(f"Loaded {sum(len(v) for v in data.values())} candles, {len(data)} symbols\n")

    # EMA50_DISTANCE sweep
    print("=" * 80)
    print("EMA50_DISTANCE PARAMETER SWEEP")
    print("=" * 80)
    print(f"{'DIST%':>6} {'SL%':>5} {'HOLD':>5} {'TRADES':>7} {'WR%':>6} {'NET':>10} {'PF':>6}")
    print("-" * 55)
    for dist in [-4, -5, -6, -7]:
        for sl in [0.03, 0.04, 0.05]:
            for hold in [5, 7, 10]:
                trades = backtest_ema50(data, dist, sl, hold)
                if trades:
                    wins = sum(1 for t in trades if t['pnl'] > 0)
                    net = sum(t['pnl'] for t in trades)
                    gw = sum(t['pnl'] for t in trades if t['pnl'] > 0)
                    gl = abs(sum(t['pnl'] for t in trades if t['pnl'] <= 0))
                    pf = gw / gl if gl > 0 else 999
                    wr = wins / len(trades) * 100
                    marker = " ***" if net > 100000 else ""
                    print(f"{dist:>6} {sl:>5.2f} {hold:>5} {len(trades):>7} {wr:>6.1f} â‚¹{net:>9,.0f} {pf:>6.2f}{marker}")

    # RSI_OVERSOLD sweep
    print("\n" + "=" * 80)
    print("RSI_OVERSOLD PARAMETER SWEEP")
    print("=" * 80)
    print(f"{'RSI':>5} {'SL%':>5} {'TGT%':>5} {'HOLD':>5} {'TRADES':>7} {'WR%':>6} {'NET':>10} {'PF':>6}")
    print("-" * 60)
    for rsi_thresh in [30, 33, 35, 38, 40]:
        for sl in [0.02, 0.03, 0.04]:
            for tgt in [0.02, 0.03, 0.04]:
                for hold in [3, 5, 7]:
                    trades = backtest_rsi(data, rsi_thresh, sl, tgt, hold)
                    if trades:
                        wins = sum(1 for t in trades if t['pnl'] > 0)
                        net = sum(t['pnl'] for t in trades)
                        gw = sum(t['pnl'] for t in trades if t['pnl'] > 0)
                        gl = abs(sum(t['pnl'] for t in trades if t['pnl'] <= 0))
                        pf = gw / gl if gl > 0 else 999
                        wr = wins / len(trades) * 100
                        marker = " ***" if net > 100000 else ""
                        print(f"{rsi_thresh:>5} {sl:>5.2f} {tgt:>5.2f} {hold:>5} {len(trades):>7} {wr:>6.1f} â‚¹{net:>9,.0f} {pf:>6.2f}{marker}")

