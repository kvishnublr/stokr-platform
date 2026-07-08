#!/usr/bin/env python3
"""
Comprehensive daily strategy backtester matching Java BacktestController logic.
Tests EMA50D variants + Gap+Volume strategy.
"""
import subprocess, os, sys
from collections import defaultdict

BROKERAGE = 80
CAPITAL = 100000

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

def compute_ema(closes, period):
    ema = [0.0] * len(closes)
    if len(closes) < period: return ema
    k = 2.0 / (period + 1)
    ema[period-1] = sum(closes[:period]) / period
    for i in range(period, len(closes)):
        ema[i] = closes[i] * k + ema[i-1] * (1 - k)
    return ema

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

def compute_sma(values, period):
    result = [0.0] * len(values)
    for i in range(period-1, len(values)):
        result[i] = sum(values[i-period+1:i+1]) / period
    return result

def backtest_daily(symbols_data, signal_fn, max_hold=7, trail_trigger=0.5, trail_dist=0.3, date_filter=None):
    """
    Daily backtest matching Java logic:
    - 1 trade per day across all symbols (shared tradedDays)
    - Trailing stop with target lock
    - Max hold exit
    - Position sizing: floor(CAPITAL / entry_price) shares
    """
    # Pre-compute indicators per symbol
    sym_indicators = {}
    for sym, cndls in symbols_data.items():
        n = len(cndls)
        closes = [c['close'] for c in cndls]
        volumes = [c['volume'] for c in cndls]
        ema20 = compute_ema(closes, 20)
        ema50 = compute_ema(closes, 50)
        rsi14 = compute_rsi(closes, 14)
        sma20 = compute_sma(volumes, 20)
        sym_indicators[sym] = {
            'closes': closes, 'ema20': ema20, 'ema50': ema50,
            'rsi14': rsi14, 'sma20_vol': sma20
        }

    # Collect all (date, sym, signal) tuples
    all_signals = []
    for sym, cndls in symbols_data.items():
        n = len(cndls)
        ind = sym_indicators[sym]
        for i in range(55, n):
            ts = cndls[i]['timestamp']
            d = ts[:10]
            if date_filter and d < date_filter[0]: continue
            if date_filter and d > date_filter[1]: continue
            sig = signal_fn(cndls, i, ind, sym)
            if sig is not None:
                all_signals.append((d, i, sym, sig, cndls[i]))

    # Sort by date, then by signal strength
    all_signals.sort(key=lambda x: (x[0], -x[3].get('score', 0)))

    # Execute: 1 trade per day
    traded_days = set()
    trades = []
    for d, i, sym, sig, candle in all_signals:
        if d in traded_days: continue
        cndls = symbols_data[sym]
        n = len(cndls)
        entry = sig['entry']
        if entry <= 0: continue
        sl_price = sig['sl']
        target = sig['target']
        side = sig.get('side', 'BUY')

        qty = int(CAPITAL / entry)
        if qty <= 0: continue
        trade_capital = qty * entry

        # Trailing stop simulation
        current_sl = sl_price
        best_price = entry
        trail_activated = False
        target_locked = False
        exit_price = None
        exit_type = None

        for j in range(i + 1, min(i + 1 + max_hold, n)):
            hi = cndls[j]['high']
            lo = cndls[j]['low']

            if side == 'BUY':
                if hi > best_price:
                    best_price = hi
                    gain_pct = (best_price - entry) / entry * 100
                    if gain_pct >= trail_trigger:
                        trail_activated = True

                if not target_locked and hi >= target:
                    target_locked = True
                    trail_activated = True
                    if target > current_sl:
                        current_sl = target

                if trail_activated:
                    new_trail = best_price * (1 - trail_dist / 100)
                    if new_trail > current_sl:
                        current_sl = new_trail

                if lo <= current_sl:
                    exit_price = current_sl
                    exit_type = 'TARGET_HIT' if target_locked else ('TRAIL_SL' if trail_activated else 'SL_HIT')
                    break
            else:
                if lo < best_price:
                    best_price = lo
                    gain_pct = (entry - best_price) / entry * 100
                    if gain_pct >= trail_trigger:
                        trail_activated = True

                if not target_locked and lo <= target:
                    target_locked = True
                    trail_activated = True
                    if target < current_sl:
                        current_sl = target

                if trail_activated:
                    new_trail = best_price * (1 + trail_dist / 100)
                    if new_trail < current_sl:
                        current_sl = new_trail

                if hi >= current_sl:
                    exit_price = current_sl
                    exit_type = 'TARGET_HIT' if target_locked else ('TRAIL_SL' if trail_activated else 'SL_HIT')
                    break

        if exit_price is None:
            exit_idx = min(i + max_hold, n - 1)
            exit_price = cndls[exit_idx]['close']
            exit_type = 'MAX_HOLD'

        if side == 'BUY':
            move_pct = (exit_price - entry) / entry
        else:
            move_pct = (entry - exit_price) / entry
        pnl = round(move_pct * trade_capital, 2) - BROKERAGE

        traded_days.add(d)
        trades.append({
            'symbol': sym, 'side': side, 'entry': entry, 'exit': exit_price,
            'pnl': pnl, 'exit_type': exit_type, 'date': d,
            'qty': qty, 'trade_capital': trade_capital,
            'reason': sig.get('reason', '')
        })

    return trades

def print_report(name, trades):
    if not trades:
        print(f"  {name}: 0 trades\n")
        return
    
    wins = [t for t in trades if t['pnl'] > 0]
    losses = [t for t in trades if t['pnl'] <= 0]
    net = sum(t['pnl'] for t in trades)
    gw = sum(t['pnl'] for t in wins) if wins else 0
    gl = abs(sum(t['pnl'] for t in losses)) if losses else 1
    pf = gw / gl if gl > 0 else 999
    wr = len(wins) / len(trades) * 100
    
    # Max drawdown
    cumulative = 0
    peak = 0
    max_dd = 0
    for t in trades:
        cumulative += t['pnl']
        if cumulative > peak: peak = cumulative
        dd = peak - cumulative
        if dd > max_dd: max_dd = dd
    
    # Monthly
    monthly = defaultdict(lambda: {'trades': 0, 'wins': 0, 'pnl': 0})
    for t in trades:
        m = t['date'][:7]
        monthly[m]['trades'] += 1
        if t['pnl'] > 0: monthly[m]['wins'] += 1
        monthly[m]['pnl'] += t['pnl']
    
    exits = defaultdict(int)
    for t in trades: exits[t['exit_type']] += 1
    
    buy_t = [t for t in trades if t['side'] == 'BUY']
    sell_t = [t for t in trades if t['side'] == 'SELL']
    
    print(f"  {name}")
    print(f"    Trades: {len(trades)} ({len(buy_t)} LONG, {len(sell_t)} SHORT)")
    print(f"    Win Rate: {wr:.1f}% | PF: {pf:.2f} | Net: ₹{net:,.0f}")
    print(f"    Max Drawdown: ₹{max_dd:,.0f}")
    print(f"    Exits: {dict(exits)}")
    for m in sorted(monthly.keys()):
        d = monthly[m]
        mw = d['wins'] / d['trades'] * 100 if d['trades'] > 0 else 0
        print(f"      {m}: {d['trades']:3d} trades, {mw:5.1f}% WR, ₹{d['pnl']:>8,.0f}")
    
    # Top/Bottom 3
    top3 = sorted(wins, key=lambda x: x['pnl'], reverse=True)[:3]
    bot3 = sorted(losses, key=lambda x: x['pnl'])[:3]
    if top3:
        print(f"    Top: {', '.join(f'{t['symbol']} ₹{t['pnl']:,.0f} ({t['exit_type']})' for t in top3)}")
    if bot3:
        print(f"    Bot: {', '.join(f'{t['symbol']} ₹{t['pnl']:,.0f} ({t['exit_type']})' for t in bot3)}")
    print()


if __name__ == '__main__':
    print("Loading daily candles...")
    data = get_daily_candles()
    total = sum(len(v) for v in data.values())
    print(f"Loaded {total} candles, {len(data)} symbols\n")

    # Filter to NIFTY_50
    nifty50 = set(['ADANIPORTS','APOLLOHOSP','AXISBANK','BAJAJ-AUTO','BAJFINANCE',
               'BAJAJFINSV','BPCL','BRITANNIA','CIPLA','DRREDDY','EICHERMOT',
               'GRASIM','HCLTECH','HDFCBANK','HDFCLIFE','HEROMOTOCO','HINDALCO',
               'HINDUNILVR','ICICIBANK','INDUSINDBK','INFY','ITC','KOTAKBANK',
               'LT','M&M','MARUTI','NESTLEIND','NTPC','ONGC','POWERGRID',
               'RELIANCE','SBICARD','SBILIFE','SBIN','SUNPHARMA','TATACONSUM',
               'TATAMOTORS','TATASTEEL','TCS','TECHM','TITAN','TRENT',
               'ULTRACEMCO','WIPRO'])
    data50 = {k: v for k, v in data.items() if k in nifty50}

    FILTER_3M = ('2026-04-07', '2026-07-07')
    FILTER_12M = ('2025-07-07', '2026-07-07')

    print("=" * 70)
    print("  EMA50D VARIANTS — 3-Month Backtest (Apr 7 - Jul 7, 2026)")
    print("=" * 70)

    # ─── VARIANT 1: EMA50D Original (dist < -5%, SL 4%, Tgt=EMA50) ──
    def ema50d_v1(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        dist = (c['close'] - ind['ema50'][i]) / ind['ema50'][i] * 100
        if dist > -5.0 or dist < -15: return None
        if c['close'] >= c['open']: return None  # RED day only
        return {'entry': c['close'], 'sl': c['close'] * 0.96, 'target': ind['ema50'][i], 'score': abs(dist), 'side': 'BUY', 'reason': f'v1 dist={dist:.1f}'}
    t = backtest_daily(data50, ema50d_v1, max_hold=7, date_filter=FILTER_3M)
    print_report("EMA50D v1: dist<-5%, RED only, SL 4%, Tgt=EMA50, Hold 7d", t)

    # ─── VARIANT 2: EMA50D Relaxed (dist < -3%, any day) ──
    def ema50d_v2(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        dist = (c['close'] - ind['ema50'][i]) / ind['ema50'][i] * 100
        if dist > -3.0 or dist < -15: return None
        return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': ind['ema50'][i], 'score': abs(dist), 'side': 'BUY', 'reason': f'v2 dist={dist:.1f}'}
    t = backtest_daily(data50, ema50d_v2, max_hold=7, date_filter=FILTER_3M)
    print_report("EMA50D v2: dist<-3%, ANY day, SL 3%, Tgt=EMA50, Hold 7d", t)

    # ─── VARIANT 3: EMA50D + RSI combo (dist < -4%, RSI < 40) ──
    def ema50d_v3(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        dist = (c['close'] - ind['ema50'][i]) / ind['ema50'][i] * 100
        if dist > -4.0 or dist < -15: return None
        if ind['rsi14'][i] > 40: return None
        return {'entry': c['close'], 'sl': c['close'] * 0.96, 'target': ind['ema50'][i], 'score': abs(dist) + (40 - ind['rsi14'][i]), 'side': 'BUY', 'reason': f'v3 dist={dist:.1f} rsi={ind["rsi14"][i]:.0f}'}
    t = backtest_daily(data50, ema50d_v3, max_hold=7, date_filter=FILTER_3M)
    print_report("EMA50D v3: dist<-4%, RSI<40, SL 4%, Tgt=EMA50, Hold 7d", t)

    # ─── VARIANT 4: EMA50D Wide Target (dist < -4%, Tgt = +2% from entry) ──
    def ema50d_v4(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        dist = (c['close'] - ind['ema50'][i]) / ind['ema50'][i] * 100
        if dist > -4.0 or dist < -15: return None
        return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': c['close'] * 1.02, 'score': abs(dist), 'side': 'BUY', 'reason': f'v4 dist={dist:.1f}'}
    t = backtest_daily(data50, ema50d_v4, max_hold=5, date_filter=FILTER_3M)
    print_report("EMA50D v4: dist<-4%, SL 3%, Tgt=+2%, Hold 5d", t)

    # ─── VARIANT 5: EMA50D + Volume surge (dist < -4%, vol > 1.5x avg) ──
    def ema50d_v5(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        dist = (c['close'] - ind['ema50'][i]) / ind['ema50'][i] * 100
        if dist > -4.0 or dist < -15: return None
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        if vol_ratio < 1.5: return None
        return {'entry': c['close'], 'sl': c['close'] * 0.96, 'target': ind['ema50'][i], 'score': abs(dist) + vol_ratio, 'side': 'BUY', 'reason': f'v5 dist={dist:.1f} vol={vol_ratio:.1f}x'}
    t = backtest_daily(data50, ema50d_v5, max_hold=7, date_filter=FILTER_3M)
    print_report("EMA50D v5: dist<-4%, Vol>1.5x, SL 4%, Tgt=EMA50, Hold 7d", t)

    # ─── VARIANT 6: EMA50D Deep (dist < -6%, any day) ──
    def ema50d_v6(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        dist = (c['close'] - ind['ema50'][i]) / ind['ema50'][i] * 100
        if dist > -6.0 or dist < -15: return None
        return {'entry': c['close'], 'sl': c['close'] * 0.95, 'target': ind['ema50'][i], 'score': abs(dist), 'side': 'BUY', 'reason': f'v6 dist={dist:.1f}'}
    t = backtest_daily(data50, ema50d_v6, max_hold=10, date_filter=FILTER_3M)
    print_report("EMA50D v6: dist<-6%, SL 5%, Tgt=EMA50, Hold 10d", t)

    print("=" * 70)
    print("  GAP+VOLUME STRATEGY — 3-Month Backtest")
    print("=" * 70)

    # ─── GAP+VOL 1: Buy gap-down on high volume ──
    def gap_vol_1(cndls, i, ind, sym):
        if i < 2: return None
        c = cndls[i]
        prev = cndls[i-1]
        if prev['close'] <= 0 or c['volume'] <= 0: return None
        gap_pct = (c['open'] - prev['close']) / prev['close'] * 100
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        # Gap down > 1.5% on volume > 2x average
        if gap_pct < -1.5 and vol_ratio > 2.0:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': prev['close'], 'score': abs(gap_pct) + vol_ratio, 'side': 'BUY', 'reason': f'gap={gap_pct:.1f}% vol={vol_ratio:.1f}x'}
        return None
    t = backtest_daily(data50, gap_vol_1, max_hold=5, date_filter=FILTER_3M)
    print_report("Gap+Vol v1: Gap<-1.5% + Vol>2x, SL 3%, Tgt=prev close, Hold 5d", t)

    # ─── GAP+VOL 2: Buy gap-down > 1% with RSI < 35 ──
    def gap_vol_2(cndls, i, ind, sym):
        if i < 2: return None
        c = cndls[i]
        prev = cndls[i-1]
        if prev['close'] <= 0 or c['volume'] <= 0: return None
        gap_pct = (c['open'] - prev['close']) / prev['close'] * 100
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        if gap_pct < -1.0 and ind['rsi14'][i] < 35:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': prev['close'], 'score': abs(gap_pct) + (35 - ind['rsi14'][i]), 'side': 'BUY', 'reason': f'gap={gap_pct:.1f}% rsi={ind["rsi14"][i]:.0f}'}
        return None
    t = backtest_daily(data50, gap_vol_2, max_hold=5, date_filter=FILTER_3M)
    print_report("Gap+Vol v2: Gap<-1% + RSI<35, SL 3%, Tgt=prev close, Hold 5d", t)

    # ─── GAP+VOL 3: Buy 2-day cumulative drop > 4% with vol surge ──
    def gap_vol_3(cndls, i, ind, sym):
        if i < 2: return None
        c = cndls[i]
        prev = cndls[i-1]
        if prev['close'] <= 0 or c['volume'] <= 0: return None
        drop_2d = (c['close'] - cndls[i-2]['close']) / cndls[i-2]['close'] * 100
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        if drop_2d < -4.0 and vol_ratio > 1.5:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': c['close'] * 1.03, 'score': abs(drop_2d) + vol_ratio, 'side': 'BUY', 'reason': f'2d_drop={drop_2d:.1f}% vol={vol_ratio:.1f}x'}
        return None
    t = backtest_daily(data50, gap_vol_3, max_hold=5, date_filter=FILTER_3M)
    print_report("Gap+Vol v3: 2-day drop>4% + Vol>1.5x, SL 3%, Tgt=+3%, Hold 5d", t)

    # ─── GAP+VOL 4: Buy red candle > 2% on volume > 2x, target prev close ──
    def gap_vol_4(cndls, i, ind, sym):
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        drop_pct = (c['close'] - c['open']) / c['open'] * 100
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        if drop_pct < -2.0 and vol_ratio > 2.0:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': c['open'], 'score': abs(drop_pct) + vol_ratio, 'side': 'BUY', 'reason': f'drop={drop_pct:.1f}% vol={vol_ratio:.1f}x'}
        return None
    t = backtest_daily(data50, gap_vol_4, max_hold=5, date_filter=FILTER_3M)
    print_report("Gap+Vol v4: Red >2% + Vol>2x, SL 3%, Tgt=open, Hold 5d", t)

    # ─── GAP+VOL 5: Buy after 3 consecutive red days with vol ──
    def gap_vol_5(cndls, i, ind, sym):
        if i < 3: return None
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        # Check 3 consecutive red days
        red_count = 0
        for j in range(i-2, i+1):
            if cndls[j]['close'] < cndls[j]['open']:
                red_count += 1
        if red_count < 3: return None
        total_drop = (c['close'] - cndls[i-2]['open']) / cndls[i-2]['open'] * 100
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        if total_drop < -3.0 and vol_ratio > 1.2:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': c['close'] * 1.03, 'score': abs(total_drop) + vol_ratio, 'side': 'BUY', 'reason': f'3red drop={total_drop:.1f}% vol={vol_ratio:.1f}x'}
        return None
    t = backtest_daily(data50, gap_vol_5, max_hold=5, date_filter=FILTER_3M)
    print_report("Gap+Vol v5: 3-red-days + drop>3% + Vol>1.2x, SL 3%, Tgt=+3%, Hold 5d", t)

    # ─── GAP+VOL 6: Buy when close < lower Bollinger(20,2) + vol surge ──
    def gap_vol_6(cndls, i, ind, sym):
        if i < 20: return None
        c = cndls[i]
        if c['volume'] <= 0 or c['close'] < 50: return None
        closes = [cndls[j]['close'] for j in range(i-19, i+1)]
        sma = sum(closes) / 20
        std = (sum((x - sma)**2 for x in closes) / 20) ** 0.5
        lower_bb = sma - 2 * std
        vol_ratio = c['volume'] / ind['sma20_vol'][i] if ind['sma20_vol'][i] > 0 else 0
        if c['close'] < lower_bb and vol_ratio > 1.5:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': sma, 'score': (sma - c['close']) / sma * 100 + vol_ratio, 'side': 'BUY', 'reason': f'below_bb vol={vol_ratio:.1f}x'}
        return None
    t = backtest_daily(data50, gap_vol_6, max_hold=7, date_filter=FILTER_3M)
    print_report("Gap+Vol v6: Below lower BB(20,2) + Vol>1.5x, SL 3%, Tgt=SMA20, Hold 7d", t)

    # Also run the best candidates on 12-month for validation
    print("=" * 70)
    print("  BEST CANDIDATES — 12-Month Validation (Jul 2025 - Jul 2026)")
    print("=" * 70)

    # v2 (loosest EMA50D)
    t = backtest_daily(data50, ema50d_v2, max_hold=7, date_filter=FILTER_12M)
    print_report("EMA50D v2: dist<-3%, ANY day, SL 3%, Tgt=EMA50, Hold 7d", t)

    # v3 (EMA50D + RSI)
    t = backtest_daily(data50, ema50d_v3, max_hold=7, date_filter=FILTER_12M)
    print_report("EMA50D v3: dist<-4%, RSI<40, SL 4%, Tgt=EMA50, Hold 7d", t)

    # gap_vol_4 (red drop + vol)
    t = backtest_daily(data50, gap_vol_4, max_hold=5, date_filter=FILTER_12M)
    print_report("Gap+Vol v4: Red >2% + Vol>2x, SL 3%, Tgt=open, Hold 5d", t)

    # gap_vol_6 (below BB + vol)
    t = backtest_daily(data50, gap_vol_6, max_hold=7, date_filter=FILTER_12M)
    print_report("Gap+Vol v6: Below lower BB(20,2) + Vol>1.5x, SL 3%, Tgt=SMA20, Hold 7d", t)

    # OB baseline for comparison
    def ob_baseline(cndls, i, ind, sym):
        c = cndls[i]
        if i < 1 or c['volume'] <= 0 or c['close'] < 50: return None
        prev = cndls[i-1]
        if prev['close'] <= 0: return None
        drop_pct = (c['close'] - prev['close']) / prev['close'] * 100
        if drop_pct < -3.0:
            return {'entry': c['close'], 'sl': c['close'] * 0.97, 'target': c['close'] * 1.015, 'score': abs(drop_pct), 'side': 'BUY', 'reason': f'OB drop={drop_pct:.1f}%'}
        return None
    t = backtest_daily(data50, ob_baseline, max_hold=7, date_filter=FILTER_3M)
    print_report("OVERSOLD_BOUNCE baseline: drop>3%, SL 3%, Tgt=+1.5%, Hold 7d", t)
    t = backtest_daily(data50, ob_baseline, max_hold=7, date_filter=FILTER_12M)
    print_report("OVERSOLD_BOUNCE baseline (12M): drop>3%, SL 3%, Tgt=+1.5%, Hold 7d", t)
