import json

with open('C:\\Users\\itsvi\\Desktop\\work_new\\stokr-platform\\stokr-lite\\backend\\mvr_result.json') as f:
    data = json.load(f)

print("=" * 80)
print("MICRO V-REVERSAL BACKTEST RESULTS")
print("Strategy: 3-bar drop >=1% -> reclaim -> LONG | SL=1% | Target=1.5%")
print("=" * 80)

print(f"\n{'Metric':<30} {'Value':>15}")
print("-" * 50)
for name, key, fmt in [
    ('Trades', 'totalTrades', 'd'),
    ('Wins', 'winCount', 'd'),
    ('Losses', 'lossCount', 'd'),
    ('Win Rate', 'winRate', '.1f'),
    ('Gross PnL', 'totalPnL', ',.2f'),
    ('Brokerage', 'totalBrokerage', ',.2f'),
    ('Net PnL', 'netPnL', ',.2f'),
    ('Avg PnL/Trade', 'avgPnL', ',.2f'),
    ('Profit Factor', 'profitFactor', '.2f'),
    ('Max Drawdown', 'maxDrawdown', ',.2f'),
]:
    v = data.get(key, 0)
    if fmt == 'd':
        print(f"  {name:<28} {v:>15d}")
    elif fmt == '.1f':
        print(f"  {name:<28} {v:>14.1f}%")
    else:
        print(f"  {name:<28} ₹{v:>13,.2f}")

print(f"\n{'TRADE LIST':^50}")
print("-" * 80)
print(f"{'#':>3} {'Date':>12} {'Symbol':<14} {'Entry':>10} {'Exit':>10} {'PnL':>9} {'Net':>9} {'Type':<15}")
print("-" * 80)

cum = 0
for i, t in enumerate(data['trades'], 1):
    cum += t['netPnl']
    print(f"{i:>3} {t['entryTime'][:10]:>12} {t['symbol']:<14} {t['entryPrice']:>10.2f} {t['exitPrice']:>10.2f} {t['pnl']:>+9.2f} {t['netPnl']:>+9.2f} {t['exitType']:<15}")

print("-" * 80)
print(f"  TOTAL: ₹{data['netPnL']:>10,.2f} | {data['totalTrades']} trades | {data['winCount']}/{data['lossCount']} W/L")

# Comparison with OB
print(f"\n{'COMPARISON WITH OVERSOLD BOUNCE':^50}")
print("-" * 60)
print(f"  {'Metric':<25} {'OB (Daily)':>15} {'MVR (Intraday)':>15}")
print("-" * 60)
print(f"  {'Trades (1 month)':<25} {'~7':>15} {data['totalTrades']:>15}")
print(f"  {'Win Rate':<25} {'83.3%':>15} {data['winRate']:.1f}%")
print(f"  {'Net PnL (1 month)':<25} {'₹5,103':>15} ₹{data['netPnL']:,.0f}")
print(f"  {'Profit Factor':<25} {'3.09':>15} {data['profitFactor']:.2f}")
print(f"  {'Max Drawdown':<25} {'₹7,541':>15} ₹{data['maxDrawdown']:,.0f}")
print(f"  {'Capital':<25} {'₹1,00,000':>15} ₹1,00,000")
print(f"  {'Hold':<25} {'1-7 days':>15} {'<15 min':>15}")
print(f"  {'Timeframe':<25} {'Daily (EOD)':>15} {'1-min':>15}")
