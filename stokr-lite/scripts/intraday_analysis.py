#!/usr/bin/env python3
"""
Deep intraday strategy analysis.
Tests multiple candidate strategies on 1-minute NIFTY_50 data.
"""
import subprocess, os, sys
from collections import defaultdict

def get_1min_candles():
    result = subprocess.run(
        ['psql', '-h', 'localhost', '-p', '5432', '-U', 'postgres', '-d', 'stokr_lite',
         '-t', '-A', '-F', '|', '-c',
         """SELECT symbol, timestamp, open, high, low, close, volume
            FROM candle_data WHERE timeframe='1min'
            AND timestamp >= '2026-04-07'
            ORDER BY symbol, timestamp"""],
        capture_output=True, text=True, env={**os.environ, 'PGPASSWORD': 'stokr2026'}
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

def get_daily_candles():
    result = subprocess.run(
        ['psql', '-h', 'localhost', '-p', '5432', '-U', 'postgres', '-d', 'stokr_lite',
         '-t', '-A', '-F', '|', '-c',
         """SELECT symbol, timestamp, open, high, low, close, volume
            FROM candle_data WHERE timeframe='daily'
            ORDER BY symbol, timestamp"""],
        capture_output=True, text=True, env={**os.environ, 'PGPASSWORD': 'stokr2026'}
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

BROKERAGE = 80

def backtest_intraday(symbols_data, signal_fn, capital=100000):
    """Generic intraday backtest. signal_fn(candles, i) -> 'BUY', 'SELL', or None"""
    trades = []
    for sym, cndls in symbols_data.items():
        n = len(cndls)
        if n < 60: continue
        i = 0
        while i < n - 1:
            sig = signal_fn(cndls, i)
            if sig is None:
                i += 1
                continue
            # Find entry at next candle open
            if i + 1 >= n: break
            entry = cndls[i+1]['open']
            if entry <= 0: 
                i += 1
                continue
            entry_idx = i + 1
            entry_ts = cndls[entry_idx]['timestamp']
            entry_date = entry_ts[:10]
            entry_hour = int(entry_ts[11:13]) if len(entry_ts) > 13 else 0
            entry_min = int(entry_ts[14:16]) if len(entry_ts) > 15 else 0
            
            # Skip if entry is too late (> 14:30)
            if entry_hour > 14 or (entry_hour == 14 and entry_min > 30):
                i += 1
                continue
            
            # Intraday exit: end of day (15:15) or SL/Target
            sl = entry * 0.003 if sig == 'BUY' else entry * 1.003  # 0.3% SL
            tgt = entry * 1.005 if sig == 'BUY' else entry * 0.995  # 0.5% target
            
            exit_price = None
            exit_type = None
            for j in range(entry_idx + 1, n):
                c = cndls[j]
                c_date = c['timestamp'][:10]
                c_hour = int(c['timestamp'][11:13]) if len(c['timestamp']) > 13 else 0
                c_min = int(c['timestamp'][14:16]) if len(c['timestamp']) > 15 else 0
                
                # Must exit by 15:15
                if c_date != entry_date:
                    # Use previous candle's close as EOD exit
                    exit_price = cndls[j-1]['close']
                    exit_type = 'EOD'
                    break
                
                if c_hour >= 15 and c_min >= 15:
                    exit_price = c['close']
                    exit_type = 'EOD'
                    break
                
                if sig == 'BUY':
                    if c['low'] <= sl:
                        exit_price = sl
                        exit_type = 'SL'
                        break
                    if c['high'] >= tgt:
                        exit_price = tgt
                        exit_type = 'TARGET'
                        break
                else:  # SELL
                    if c['high'] >= sl:
                        exit_price = sl
                        exit_type = 'SL'
                        break
                    if c['low'] <= tgt:
                        exit_price = tgt
                        exit_type = 'TARGET'
                        break
            
            if exit_price is None:
                exit_price = cndls[min(entry_idx + 390, n-1)]['close']
                exit_type = 'EOD'
            
            qty = int(capital / entry)
            if qty <= 0:
                i += 1
                continue
            if sig == 'BUY':
                pnl = (exit_price - entry) * qty - BROKERAGE
            else:
                pnl = (entry - exit_price) * qty - BROKERAGE
            
            trades.append({
                'symbol': sym, 'side': sig, 'entry': entry, 'exit': exit_price,
                'pnl': pnl, 'exit_type': exit_type, 'date': entry_date
            })
            
            # Skip to next day after intraday trade
            i = entry_idx + 1
            while i < n and cndls[i]['timestamp'][:10] == entry_date:
                i += 1
    return trades

def print_stats(name, trades):
    if not trades:
        print(f"  {name}: 0 trades")
        return
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    net = sum(t['pnl'] for t in trades)
    gw = sum(t['pnl'] for t in wins) if wins else 0
    gl = abs(sum(t['pnl'] for t in losses)) if losses else 1
    pf = gw / gl if gl > 0 else 999
    wr = len(wins) / len(trades) * 100
    
    # Monthly breakdown
    monthly = defaultdict(lambda: {'trades': 0, 'wins': 0, 'pnl': 0})
    for t in trades:
        m = t['date'][:7]
        monthly[m]['trades'] += 1
        if t['pnl'] > 0: monthly[m]['wins'] += 1
        monthly[m]['pnl'] += t['pnl']
    
    # Exit type breakdown
    exits = defaultdict(int)
    for t in trades:
        exits[t['exit_type']] += 1
    
    # Side breakdown
    buy_trades = [t for t in trades if t['side'] == 'BUY']
    sell_trades = [t for t in trades if t['side'] == 'SELL']
    buy_pnl = sum(t['pnl'] for t in buy_trades)
    sell_pnl = sum(t['pnl'] for t in sell_trades)
    
    print(f"  {name}")
    print(f"    Trades: {len(trades)} ({len(buy_trades)} LONG, {len(sell_trades)} SHORT)")
    print(f"    Win Rate: {wr:.1f}% | PF: {pf:.2f} | Net: ₹{net:,.0f}")
    print(f"    Long PnL: ₹{buy_pnl:,.0f} | Short PnL: ₹{sell_pnl:,.0f}")
    print(f"    Exits: {dict(exits)}")
    print(f"    Monthly:")
    for m in sorted(monthly.keys()):
        d = monthly[m]
        mw = d['wins'] / d['trades'] * 100 if d['trades'] > 0 else 0
        print(f"      {m}: {d['trades']:3d} trades, {mw:5.1f}% WR, ₹{d['pnl']:>8,.0f}")
    # Top 3 winners
    top_w = sorted(wins, key=lambda x: x['pnl'], reverse=True)[:3]
    if top_w:
        print(f"    Top winners: {', '.join(f'{t['symbol']} ₹{t['pnl']:,.0f}' for t in top_w)}")


if __name__ == '__main__':
    print("Loading 1-min candles...")
    data = get_1min_candles()
    total = sum(len(v) for v in data.values())
    print(f"Loaded {total} candles, {len(data)} symbols\n")
    
    # Filter to NIFTY_50 only
    nifty50 = ['ADANIPORTS','APOLLOHOSP','AXISBANK','BAJAJ-AUTO','BAJFINANCE',
               'BAJAJFINSV','BPCL','BRITANNIA','CIPLA','DRREDDY','EICHERMOT',
               'GRASIM','HCLTECH','HDFCBANK','HDFCLIFE','HEROMOTOCO','HINDALCO',
               'HINDUNILVR','ICICIBANK','INDUSINDBK','INFY','ITC','KOTAKBANK',
               'LT','M&M','MARUTI','NESTLEIND','NTPC','ONGC','POWERGRID',
               'RELIANCE','SBICARD','SBILIFE','SBIN','SUNPHARMA','TATACONSUM',
               'TATAMOTORS','TATASTEEL','TCS','TECHM','TITAN','TRENT',
               'ULTRACEMCO','WIPRO']
    data_filtered = {k: v for k, v in data.items() if k in nifty50}
    print(f"NIFTY_50: {len(data_filtered)} symbols\n")

    print("=" * 70)
    print("INTRADAY STRATEGY SCREENING")
    print("=" * 70)

    # ─── STRATEGY 1: VWAP BOUNCE (BUY) ─────────────────────────
    def vwap_bounce(cndls, i):
        if i < 60: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # Compute 20-period VWAP
        vwap = sum(cndls[j]['close'] * cndls[j]['volume'] for j in range(i-19, i+1)) / max(1, sum(cndls[j]['volume'] for j in range(i-19, i+1)))
        # Price touches/crosses VWAP from below
        prev = cndls[i-1]
        prev_vwap = sum(cndls[j]['close'] * cndls[j]['volume'] for j in range(i-20, i)) / max(1, sum(cndls[j]['volume'] for j in range(i-20, i)))
        if prev['close'] < prev_vwap and c['close'] > vwap:
            return 'BUY'
        return None
    
    trades = backtest_intraday(data_filtered, vwap_bounce)
    print_stats("VWAP Bounce (cross above 20-period VWAP)", trades)

    # ─── STRATEGY 2: OPENING RANGE BREAKOUT (LONG) ─────────────
    def orb_breakout(cndls, i):
        if i < 60: return None
        c = cndls[i]
        # Check if we're at 9:30 (15 min after open)
        ts = c['timestamp']
        hour = int(ts[11:13])
        minute = int(ts[14:16])
        if hour != 9 or minute != 30: return None
        # First 15 min range
        day_start = i - 14
        if day_start < 0: return None
        orb_high = max(cndls[j]['high'] for j in range(day_start, i))
        orb_low = min(cndls[j]['low'] for j in range(day_start, i))
        # Buy if price breaks above ORB high
        if c['close'] > orb_high:
            return 'BUY'
        return None
    
    trades = backtest_intraday(data_filtered, orb_breakout)
    print_stats("ORB Breakout (15-min range breakout LONG)", trades)

    # ─── STRATEGY 3: VOLUME SPIKE + PRICE BREAKOUT ─────────────
    def vol_spike_breakout(cndls, i):
        if i < 60: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # Average volume over last 20 periods
        avg_vol = sum(cndls[j]['volume'] for j in range(i-19, i+1)) / 20
        if avg_vol <= 0: return None
        vol_ratio = c['volume'] / avg_vol
        # Breakout: close > previous 10-bar high
        prev_high = max(cndls[j]['high'] for j in range(i-10, i))
        if vol_ratio > 2.0 and c['close'] > prev_high:
            return 'BUY'
        return None
    
    trades = backtest_intraday(data_filtered, vol_spike_breakout)
    print_stats("Volume Spike Breakout (2x vol + 10-bar high)", trades)

    # ─── STRATEGY 4: GAP FADE (SHORT oversized gaps) ───────────
    def gap_fade(cndls, i):
        if i < 30: return None
        c = cndls[i]
        prev = cndls[i-1]
        # Only at 9:15 (first candle)
        ts = c['timestamp']
        hour = int(ts[11:13])
        minute = int(ts[14:16])
        if hour != 9 or minute != 15: return None
        gap_pct = (c['open'] - prev['close']) / prev['close'] * 100
        # Fade gaps > 1%
        if gap_pct > 1.0:
            return 'SELL'
        if gap_pct < -1.0:
            return 'BUY'
        return None
    
    trades = backtest_intraday(data_filtered, gap_fade)
    print_stats("Gap Fade (>1% gap reversal)", trades)

    # ─── STRATEGY 5: VWAP MEAN REVERSION (BUY oversold from VWAP)
    def vwap_mean_reversion(cndls, i):
        if i < 60: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # Intraday VWAP
        # Find start of day
        day_date = c['timestamp'][:10]
        day_start_idx = i
        while day_start_idx > 0 and cndls[day_start_idx-1]['timestamp'][:10] == day_date:
            day_start_idx -= 1
        if i - day_start_idx < 30: return None  # need at least 30 min of data
        day_candles = cndls[day_start_idx:i+1]
        cum_vol = sum(cc['volume'] for cc in day_candles)
        if cum_vol <= 0: return None
        vwap = sum(cc['close'] * cc['volume'] for cc in day_candles) / cum_vol
        dist_pct = (c['close'] - vwap) / vwap * 100
        # Buy when 0.3%+ below VWAP
        if dist_pct < -0.3:
            return 'BUY'
        return None
    
    trades = backtest_intraday(data_filtered, vwap_mean_reversion)
    print_stats("VWAP Mean Reversion (0.3% below intraday VWAP)", trades)

    # ─── STRATEGY 6: EMA CROSS + VOLUME (9 EMA crosses 20 EMA)
    def ema_cross(cndls, i):
        if i < 30: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # Compute 9-period and 20-period EMA
        k9 = 2.0/10; k20 = 2.0/21
        ema9 = cndls[i-20]['close']
        ema20 = cndls[i-20]['close']
        for j in range(i-19, i+1):
            ema9 = cndls[j]['close'] * k9 + ema9 * (1 - k9)
            ema20 = cndls[j]['close'] * k20 + ema20 * (1 - k20)
        prev_ema9 = ema9
        prev_ema20 = ema20
        # Recompute for prev candle
        ema9_p = cndls[i-20]['close']
        ema20_p = cndls[i-20]['close']
        for j in range(i-19, i):
            ema9_p = cndls[j]['close'] * k9 + ema9_p * (1 - k9)
            ema20_p = cndls[j]['close'] * k20 + ema20_p * (1 - k20)
        
        # Bullish cross: prev ema9 < ema20, now ema9 > ema20
        if ema9_p < ema20_p and ema9 > ema20:
            return 'BUY'
        if ema9_p > ema20_p and ema9 < ema20:
            return 'SELL'
        return None
    
    trades = backtest_intraday(data_filtered, ema_cross)
    print_stats("EMA 9/20 Crossover", trades)

    # ─── STRATEGY 7: MOMENTUM PULLBACK (BUY pullback to 9-EMA in uptrend)
    def momentum_pullback(cndls, i):
        if i < 30: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # 9-period EMA
        k9 = 2.0/10
        ema9 = cndls[max(0,i-20)]['close']
        for j in range(max(0,i-19), i+1):
            ema9 = cndls[j]['close'] * k9 + ema9 * (1 - k9)
        # 20-period EMA for trend
        k20 = 2.0/21
        ema20 = cndls[max(0,i-20)]['close']
        for j in range(max(0,i-19), i+1):
            ema20 = cndls[j]['close'] * k20 + ema20 * (1 - k20)
        # Uptrend: price > ema20, ema9 > ema20
        # Pullback: price touches ema9 from above (low <= ema9, close > ema9)
        if c['close'] > ema20 and ema9 > ema20:
            if c['low'] <= ema9 * 1.001 and c['close'] > ema9:
                return 'BUY'
        return None
    
    trades = backtest_intraday(data_filtered, momentum_pullback)
    print_stats("Momentum Pullback (pullback to 9-EMA in uptrend)", trades)

    # ─── STRATEGY 8: RSI OVERSOLD INTRADAY (RSI < 20 on 5-min) ─
    def rsi_oversold_intraday(cndls, i):
        if i < 30: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # RSI on last 14 1-min candles
        gains = []
        losses = []
        for j in range(i-13, i+1):
            d = cndls[j]['close'] - cndls[j-1]['close']
            gains.append(max(d, 0))
            losses.append(max(-d, 0))
        avg_g = sum(gains) / 14
        avg_l = sum(losses) / 14
        if avg_l == 0: return None
        rsi = 100 - 100 / (1 + avg_g / avg_l)
        if rsi < 20:
            return 'BUY'
        if rsi > 80:
            return 'SELL'
        return None
    
    trades = backtest_intraday(data_filtered, rsi_oversold_intraday)
    print_stats("RSI Extremes (RSI14 <20 buy, >80 sell)", trades)

    # ─── STRATEGY 9: PREVIOUS DAY HIGH/LOW BREAK ───────────────
    def pd_hilo_break(cndls, i):
        if i < 400: return None  # need at least 1 full day
        c = cndls[i]
        ts = c['timestamp']
        hour = int(ts[11:13])
        minute = int(ts[14:16])
        # Only trigger between 10:00 and 14:00
        if hour < 10 or hour > 14: return None
        if hour == 14 and minute > 0: return None
        # Find previous day's high/low
        prev_date = None
        prev_high = None
        prev_low = None
        for j in range(i-1, max(0, i-500), -1):
            pd = cndls[j]['timestamp'][:10]
            if prev_date is None:
                prev_date = pd
            if pd != prev_date:
                # Found previous day
                prev_high = max(cndls[k]['high'] for k in range(j+1, i) if cndls[k]['timestamp'][:10] == prev_date)
                prev_low = min(cndls[k]['low'] for k in range(j+1, i) if cndls[k]['timestamp'][:10] == prev_date)
                break
        if prev_high is None or prev_low is None: return None
        if c['close'] > prev_high:
            return 'BUY'
        if c['close'] < prev_low:
            return 'SELL'
        return None
    
    trades = backtest_intraday(data_filtered, pd_hilo_break)
    print_stats("Previous Day High/Low Break", trades)

    # ─── STRATEGY 10: VWAP + RSI COMBO ─────────────────────────
    def vwap_rsi_combo(cndls, i):
        if i < 60: return None
        c = cndls[i]
        if c['volume'] <= 0: return None
        # Intraday VWAP
        day_date = c['timestamp'][:10]
        day_start_idx = i
        while day_start_idx > 0 and cndls[day_start_idx-1]['timestamp'][:10] == day_date:
            day_start_idx -= 1
        if i - day_start_idx < 20: return None
        day_candles = cndls[day_start_idx:i+1]
        cum_vol = sum(cc['volume'] for cc in day_candles)
        if cum_vol <= 0: return None
        vwap = sum(cc['close'] * cc['volume'] for cc in day_candles) / cum_vol
        dist_vwap = (c['close'] - vwap) / vwap * 100
        # RSI 14
        gains = []
        losses = []
        for j in range(i-13, i+1):
            d = cndls[j]['close'] - cndls[j-1]['close']
            gains.append(max(d, 0))
            losses.append(max(-d, 0))
        avg_g = sum(gains) / 14
        avg_l = sum(losses) / 14
        rsi = 50
        if avg_l > 0:
            rsi = 100 - 100 / (1 + avg_g / avg_l)
        # BUY: price below VWAP and RSI < 30
        if dist_vwap < -0.2 and rsi < 30:
            return 'BUY'
        # SELL: price above VWAP and RSI > 70
        if dist_vwap > 0.2 and rsi > 70:
            return 'SELL'
        return None
    
    trades = backtest_intraday(data_filtered, vwap_rsi_combo)
    print_stats("VWAP + RSI Combo (VWAP dist + RSI extremes)", trades)
