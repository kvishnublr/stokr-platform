#!/usr/bin/env python3
"""Detailed trade report with max unrealized profit for EMA50_DISTANCE"""
import json
import subprocess
import sys

def run_sql(query):
    """Run SQL query on the database"""
    cmd = f'PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "{query}"'
    result = subprocess.run(['bash', '-c', cmd], capture_output=True, text=True)
    return result.stdout.strip()

def get_daily_candles(symbol, start_date, end_date):
    """Get daily candles for a symbol during a date range"""
    query = f"""
    SELECT timestamp::date, open, high, low, close, volume
    FROM candle_data 
    WHERE symbol = '{symbol}' 
    AND timeframe = 'daily'
    AND timestamp >= '{start_date}'
    AND timestamp <= '{end_date}'
    ORDER BY timestamp
    """
    rows = run_sql(query)
    candles = []
    for row in rows.split('\n'):
        if row.strip():
            parts = row.split('|')
            if len(parts) >= 5:
                candles.append({
                    'date': parts[0],
                    'open': float(parts[1]),
                    'high': float(parts[2]),
                    'low': float(parts[3]),
                    'close': float(parts[4])
                })
    return candles

def calculate_max_unrealized_profit(entry_price, entry_date, exit_date, symbol, side='BUY'):
    """Calculate max unrealized profit during the trade"""
    candles = get_daily_candles(symbol, entry_date, exit_date)
    
    if not candles:
        return 0, 0, exit_date
    
    max_price = entry_price
    max_unrealized = 0
    max_date = entry_date
    
    for candle in candles:
        # For LONG trades, max profit is at highest high
        if side == 'BUY':
            if candle['high'] > max_price:
                max_price = candle['high']
                max_unrealized = (max_price - entry_price) / entry_price * 100
                max_date = candle['date']
        # For SHORT trades, max profit is at lowest low
        else:
            if candle['low'] < max_price:
                max_price = candle['low']
                max_unrealized = (entry_price - max_price) / entry_price * 100
                max_date = candle['date']
    
    return max_price, max_unrealized, max_date

# Get backtest results
print("Fetching backtest data...")
cmd = '''curl -s -X POST "http://localhost:8081/api/backtest/advanced?strategy=EMA50_DISTANCE&dateStart=2025-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&universe=NIFTY_100&timeframe=daily&capital=100000"'''
result = subprocess.run(['bash', '-c', cmd], capture_output=True, text=True)
data = json.loads(result.stdout)
trades = data.get('trades', [])

print(f"\n{'='*180}")
print(f"  EMA50_DISTANCE v2 — DETAILED TRADE REPORT WITH MAX UNREALIZED PROFIT")
print(f"  Universe: NIFTY_100 | Period: Jul 2025 – Jul 2026 | Capital: ₹1,00,000")
print(f"{'='*180}\n")

# Header
print(f"{'#':<4} {'Entry Date':<12} {'Symbol':<14} {'Entry ₹':<10} {'Exit Date':<12} {'Exit ₹':<10} {'Qty':<6} {'Gross P&L':<12} {'Net P&L':<12} {'Exit Type':<14} {'Max High ₹':<12} {'Max Unreal%':<12} {'Max Date':<12}")
print("-"*180)

total_pnl = 0
total_brokerage = 0
wins = 0
losses = 0
all_max_unrealized = []

for i, t in enumerate(trades):
    entry_date = t.get('entryTime', '')[:10]
    exit_date = t.get('exitTime', '')[:10]
    symbol = t.get('symbol', '')
    entry_price = t.get('entryPrice', 0)
    exit_price = t.get('exitPrice', 0)
    qty = t.get('qty', 0)
    pnl = t.get('pnl', 0)
    brokerage = t.get('brokerage', 0)
    net_pnl = t.get('netPnl', pnl)
    exit_type = t.get('exitType', '')
    
    # Calculate max unrealized profit
    max_high, max_unreal_pct, max_date = calculate_max_unrealized_profit(
        entry_price, entry_date, exit_date, symbol, 'BUY'
    )
    all_max_unrealized.append(max_unreal_pct)
    
    total_pnl += pnl
    total_brokerage += brokerage
    
    if net_pnl > 0: wins += 1
    else: losses += 1
    
    gross_str = f"+₹{pnl:.0f}" if pnl > 0 else f"-₹{abs(pnl):.0f}"
    net_str = f"+₹{net_pnl:.0f}" if net_pnl > 0 else f"-₹{abs(net_pnl):.0f}"
    
    print(f"{i+1:<4} {entry_date:<12} {symbol:<14} {entry_price:<10.2f} {exit_date:<12} {exit_price:<10.2f} {qty:<6} {gross_str:<12} {net_str:<12} {exit_type:<14} {max_high:<12.2f} {max_unreal_pct:<12.2f} {max_date:<12}")

print("-"*180)
print()

# Summary
print("="*100)
print("  SUMMARY STATISTICS")
print("="*100)
print(f"  Total Trades:           {len(trades)}")
print(f"  Wins:                   {wins} ({wins/len(trades)*100:.1f}%)")
print(f"  Losses:                 {losses} ({losses/len(trades)*100:.1f}%)")
print(f"  Gross P&L:              ₹{total_pnl:,.0f}")
print(f"  Total Brokerage:        ₹{total_brokerage:,.0f}")
print(f"  Net P&L:                ₹{data.get('netPnL', 0):,.0f}")
print(f"  Profit Factor:          {data.get('profitFactor', 0):.2f}")
print(f"  Max Drawdown:           ₹{data.get('maxDrawdown', 0):,.0f}")
print()

# Max unrealized profit stats
print("="*100)
print("  MAX UNREALIZED PROFIT ANALYSIS")
print("="*100)
if all_max_unrealized:
    avg_max_unreal = sum(all_max_unrealized) / len(all_max_unrealized)
    max_of_max = max(all_max_unrealized)
    min_of_max = min(all_max_unrealized)
    
    print(f"  Average Max Unrealized Profit:  {avg_max_unreal:.2f}%")
    print(f"  Highest Max Unrealized:         {max_of_max:.2f}%")
    print(f"  Lowest Max Unrealized:          {min_of_max:.2f}%")
    print()
    
    # Distribution
    ranges = [(0, 1), (1, 2), (2, 3), (3, 5), (5, 10), (10, 100)]
    print("  Distribution of Max Unrealized Profits:")
    for low, high in ranges:
        count = sum(1 for x in all_max_unrealized if low <= x < high)
        pct = count / len(all_max_unrealized) * 100
        bar = '█' * int(pct / 2)
        print(f"    {low:>2}-{high:>2}%: {count:>3} trades ({pct:>5.1f}%) {bar}")

# Monthly breakdown
print()
print("="*100)
print("  MONTHLY BREAKDOWN")
print("="*100)
monthly = {}
for t in trades:
    m = t.get('entryTime', '')[:7]
    if m not in monthly: 
        monthly[m] = {'trades': 0, 'wins': 0, 'pnl': 0, 'max_unreal': []}
    monthly[m]['trades'] += 1
    if t.get('netPnl', t.get('pnl', 0)) > 0: 
        monthly[m]['wins'] += 1
    monthly[m]['pnl'] += t.get('netPnl', t.get('pnl', 0))

for m in sorted(monthly.keys()):
    data_m = monthly[m]
    wr = data_m['wins']/data_m['trades']*100 if data_m['trades'] > 0 else 0
    pnl_str = f"+₹{data_m['pnl']:,.0f}" if data_m['pnl'] > 0 else f"-₹{abs(data_m['pnl']):,.0f}"
    print(f"  {m}: {data_m['trades']:>3} trades, {wr:>5.1f}% WR, {pnl_str}")

print()
print("="*100)
print("  TOP 10 TRADES BY MAX UNREALIZED PROFIT")
print("="*100)

# Sort trades by max unrealized profit
trades_with_max = []
for i, t in enumerate(trades):
    entry_date = t.get('entryTime', '')[:10]
    exit_date = t.get('exitTime', '')[:10]
    symbol = t.get('symbol', '')
    entry_price = t.get('entryPrice', 0)
    
    max_high, max_unreal_pct, max_date = calculate_max_unrealized_profit(
        entry_price, entry_date, exit_date, symbol, 'BUY'
    )
    
    trades_with_max.append({
        'symbol': symbol,
        'entry_date': entry_date,
        'entry_price': entry_price,
        'exit_date': exit_date,
        'max_high': max_high,
        'max_unreal_pct': max_unreal_pct,
        'max_date': max_date,
        'net_pnl': t.get('netPnl', t.get('pnl', 0))
    })

# Sort by max unrealized profit
trades_with_max.sort(key=lambda x: x['max_unreal_pct'], reverse=True)

print(f"\n{'Symbol':<14} {'Entry Date':<12} {'Entry ₹':<10} {'Max High ₹':<12} {'Max Unreal%':<12} {'Max Date':<12} {'Net P&L':<12}")
print("-"*80)
for t in trades_with_max[:10]:
    net_str = f"+₹{t['net_pnl']:.0f}" if t['net_pnl'] > 0 else f"-₹{abs(t['net_pnl']):.0f}"
    print(f"{t['symbol']:<14} {t['entry_date']:<12} {t['entry_price']:<10.2f} {t['max_high']:<12.2f} {t['max_unreal_pct']:<12.2f} {t['max_date']:<12} {net_str}")

print()
print("="*100)
print("  TOP 10 TRADES BY ACTUAL PROFIT")
print("="*100)

# Sort by actual net P&L
trades_by_pnl = sorted(trades_with_max, key=lambda x: x['net_pnl'], reverse=True)

print(f"\n{'Symbol':<14} {'Entry Date':<12} {'Entry ₹':<10} {'Max High ₹':<12} {'Max Unreal%':<12} {'Net P&L':<12}")
print("-"*70)
for t in trades_by_pnl[:10]:
    net_str = f"+₹{t['net_pnl']:.0f}" if t['net_pnl'] > 0 else f"-₹{abs(t['net_pnl']):.0f}"
    print(f"{t['symbol']:<14} {t['entry_date']:<12} {t['entry_price']:<10.2f} {t['max_high']:<12.2f} {t['max_unreal_pct']:<12.2f} {net_str}")
