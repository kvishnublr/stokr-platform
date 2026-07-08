#!/bin/bash
# NIFTY_100 comprehensive backtest
RESULT=$(curl -s -X POST "http://localhost:8081/api/backtest/advanced?strategy=EMA50_DISTANCE&dateStart=2025-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&universe=NIFTY_100&timeframe=daily&capital=100000")
echo "$RESULT" > /tmp/ema50d_nifty100.json

python3 << 'PYEOF'
import json

with open('/tmp/ema50d_nifty100.json') as f:
    d = json.load(f)

trades = d.get('trades', [])
print("=" * 140)
print(f"  EMA50_DISTANCE v2 — NIFTY_100 BACKTEST")
print(f"  Universe: NIFTY_100 | Period: Jul 2025 – Jul 2026 | Capital: ₹1,00,000")
print("=" * 140)
print()

print(f"{'#':<4} {'Entry Date':<12} {'Symbol':<14} {'Entry ₹':<10} {'Exit Date':<12} {'Exit ₹':<10} {'Qty':<6} {'Invest ₹':<12} {'Gross P&L':<12} {'Brokerage':<10} {'Net P&L':<12} {'Exit Type':<14}")
print("-" * 140)

total_pnl = 0
total_brokerage = 0
wins = 0
losses = 0

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
    capital = t.get('tradeCapital', 0)
    
    invest = entry_price * qty if qty > 0 else capital
    
    total_pnl += pnl
    total_brokerage += brokerage
    
    if net_pnl > 0: wins += 1
    else: losses += 1
    
    gross_str = f"+₹{pnl:.0f}" if pnl > 0 else f"-₹{abs(pnl):.0f}"
    net_str = f"+₹{net_pnl:.0f}" if net_pnl > 0 else f"-₹{abs(net_pnl):.0f}"
    
    print(f"{i+1:<4} {entry_date:<12} {symbol:<14} {entry_price:<10.2f} {exit_date:<12} {exit_price:<10.2f} {qty:<6} ₹{invest:<11,.0f} {gross_str:<12} ₹{brokerage:<9.0f} {net_str:<12} {exit_type:<14}")

print("-" * 140)
print()
print("=" * 80)
print("  NIFTY_100 SUMMARY")
print("=" * 80)
print(f"  Total Trades:       {len(trades)}")
print(f"  Wins:               {wins} ({wins/len(trades)*100:.1f}%)" if len(trades) > 0 else "  Wins: 0")
print(f"  Losses:             {losses} ({losses/len(trades)*100:.1f}%)" if len(trades) > 0 else "  Losses: 0")
print(f"  Gross P&L:          ₹{total_pnl:,.0f}")
print(f"  Total Brokerage:    ₹{total_brokerage:,.0f}")
print(f"  Net P&L:            ₹{d.get('netPnL', 0):,.0f}")
print(f"  Profit Factor:      {d.get('profitFactor', 0):.2f}")
print(f"  Max Drawdown:       ₹{d.get('maxDrawdown', 0):,.0f}")
print(f"  Avg P&L/Trade:      ₹{d.get('avgPnL', 0):,.0f}")

# Unique symbols traded
syms = list(set(t.get('symbol','') for t in trades))
print(f"  Unique Symbols:     {len(syms)}")
print(f"  Symbols:            {', '.join(sorted(syms))}")

# Per-symbol breakdown
print()
print("  PER-SYMBOL BREAKDOWN:")
print(f"  {'Symbol':<14} {'Trades':<8} {'Wins':<6} {'WR%':<8} {'Net PnL':<12}")
print("  " + "-" * 50)
by_sym = {}
for t in trades:
    s = t.get('symbol','')
    if s not in by_sym: by_sym[s] = {'trades': 0, 'wins': 0, 'pnl': 0}
    by_sym[s]['trades'] += 1
    if t.get('netPnl', t.get('pnl', 0)) > 0: by_sym[s]['wins'] += 1
    by_sym[s]['pnl'] += t.get('netPnl', t.get('pnl', 0))

for s in sorted(by_sym.keys(), key=lambda x: by_sym[x]['pnl'], reverse=True):
    d2 = by_sym[s]
    wr = d2['wins']/d2['trades']*100
    pnl_str = f"+₹{d2['pnl']:,.0f}" if d2['pnl'] > 0 else f"-₹{abs(d2['pnl']):,.0f}"
    print(f"  {s:<14} {d2['trades']:<8} {d2['wins']:<6} {wr:<8.1f} {pnl_str}")

PYEOF
