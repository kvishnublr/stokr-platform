import json

with open('/tmp/ob_result.json') as f:
    data = json.load(f)

trades = data['trades']

print(f"{'='*140}")
print(f"OVERSOLD BOUNCE BACKTEST — FULL TRADE LIST | Capital: ₹1,00,000/trade | Universe: NIFTY_50 | Daily candles | Jul 2025 – Jul 2026")
print(f"{'='*140}")
print(f"{'#':>3} {'Date':>10} {'Symbol':<14} {'Side':<5} {'Qty':>5} {'Entry':>10} {'Exit':>10} {'SL':>10} {'Tgt':>10} {'PnL':>9} {'Brg':>7} {'Net PnL':>9} {'Exit Type':<18}")
print(f"{'-'*140}")

cum = 0
for i, t in enumerate(trades, 1):
    entry_date = t['entryTime'][:10]
    cum += t['netPnl']
    print(f"{i:>3} {entry_date:>10} {t['symbol']:<14} {t['side']:<5} {t['qty']:>5} {t['entryPrice']:>10.2f} {t['exitPrice']:>10.2f} {t['stopLoss']:>10.2f} {t['target']:>10.2f} {t['pnl']:>+9.2f} {t['brokerage']:>7.2f} {t['netPnl']:>+9.2f} {t['exitType']:<18}")

print(f"{'-'*140}")
print(f"{'TOTALS':>58} {data['totalTrades']} trades | Win: {data['winCount']} | Loss: {data['lossCount']} | WR: {data['winRate']:.1f}%")
print(f"{'PnL':>58} Gross: ₹{data['totalPnL']:,.2f} | Brokerage: ₹{data['totalBrokerage']:,.2f} | Net: ₹{data['netPnL']:,.2f}")
print(f"{'METRICS':>58} PF: {data['profitFactor']:.2f} | Max DD: ₹{data['maxDrawdown']:,.2f} | Max Profit Day: ₹{data['maxProfitDay']:,.2f} | Max Loss Day: ₹{data['maxLossDay']:,.2f}")
print()

# Daily PnL summary
print(f"\n{'='*80}")
print(f"MONTHLY PnL BREAKDOWN")
print(f"{'='*80}")

monthly = {}
for t in trades:
    month = t['entryTime'][:7]
    if month not in monthly:
        monthly[month] = {'pnl': 0, 'trades': 0, 'wins': 0, 'losses': 0}
    monthly[month]['pnl'] += t['netPnl']
    monthly[month]['trades'] += 1
    if t['netPnl'] > 0:
        monthly[month]['wins'] += 1
    else:
        monthly[month]['losses'] += 1

print(f"{'Month':<10} {'Trades':>7} {'Wins':>6} {'Losses':>7} {'Win Rate':>9} {'Net PnL':>12}")
print(f"{'-'*55}")
running = 0
for m in sorted(monthly.keys()):
    d = monthly[m]
    wr = (d['wins']/d['trades']*100) if d['trades'] > 0 else 0
    running += d['pnl']
    print(f"{m:<10} {d['trades']:>7} {d['wins']:>6} {d['losses']:>7} {wr:>8.1f}% {d['pnl']:>+12.2f}")
print(f"{'-'*55}")
print(f"{'TOTAL':<10} {data['totalTrades']:>7} {data['winCount']:>6} {data['lossCount']:>7} {data['winRate']:>8.1f}% {data['netPnL']:>+12.2f}")
print(f"\nCapital deployed: ₹{data['capitalPerTrade']:,.0f} | Monthly return: ₹{data['netPnL']/12:,.2f} ({data['netPnL']/12/data['capitalPerTrade']*100:.1f}%)")

# Win/loss distribution
print(f"\n{'='*80}")
print(f"EXIT TYPE DISTRIBUTION")
print(f"{'='*80}")
exit_types = {}
for t in trades:
    et = t['exitType']
    if et not in exit_types:
        exit_types[et] = {'count': 0, 'pnl': 0}
    exit_types[et]['count'] += 1
    exit_types[et]['pnl'] += t['netPnl']

print(f"{'Exit Type':<20} {'Count':>7} {'Avg PnL':>10} {'Total PnL':>12}")
print(f"{'-'*50}")
for et in sorted(exit_types.keys(), key=lambda x: exit_types[x]['count'], reverse=True):
    d = exit_types[et]
    avg = d['pnl']/d['count']
    print(f"{et:<20} {d['count']:>7} {avg:>+10.2f} {d['pnl']:>+12.2f}")

# Top winners and losers
print(f"\n{'='*80}")
print(f"TOP 5 WINNERS")
print(f"{'='*80}")
sorted_trades = sorted(trades, key=lambda x: x['netPnl'], reverse=True)
for i, t in enumerate(sorted_trades[:5], 1):
    print(f"  {i}. {t['symbol']:<14} {t['entryTime'][:10]} ₹{t['netPnl']:>+10.2f} ({t['exitType']})")

print(f"\n{'='*80}")
print(f"TOP 5 LOSERS")
print(f"{'='*80}")
for i, t in enumerate(sorted_trades[-5:][::-1], 1):
    print(f"  {i}. {t['symbol']:<14} {t['entryTime'][:10]} ₹{t['netPnl']:>+10.2f} ({t['exitType']})")

# Streak analysis
print(f"\n{'='*80}")
print(f"STREAK ANALYSIS")
print(f"{'='*80}")
max_win_streak = max_loss_streak = cur_win = cur_loss = 0
for t in trades:
    if t['netPnl'] > 0:
        cur_win += 1
        cur_loss = 0
        max_win_streak = max(max_win_streak, cur_win)
    else:
        cur_loss += 1
        cur_win = 0
        max_loss_streak = max(max_loss_streak, cur_loss)
print(f"  Max win streak:  {max_win_streak}")
print(f"  Max loss streak: {max_loss_streak}")
