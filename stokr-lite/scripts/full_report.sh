#!/bin/bash
# Comprehensive backtest report for EMA50_DISTANCE on NIFTY_50
RESULT=$(curl -s -X POST "http://localhost:8081/api/backtest/advanced?strategy=EMA50_DISTANCE&dateStart=2025-07-08T00:00:00&dateEnd=2026-07-08T23:59:59&universe=NIFTY_50&timeframe=daily&capital=100000")
echo "$RESULT" > /tmp/ema50d_report.json

python3 << 'PYEOF'
import json

with open('/tmp/ema50d_report.json') as f:
    d = json.load(f)

trades = d.get('trades', [])
print("=" * 140)
print(f"  EMA50_DISTANCE v2 — COMPREHENSIVE TRADE REPORT")
print(f"  Universe: NIFTY_50 | Period: Jul 2025 – Jul 2026 | Capital: ₹1,00,000")
print("=" * 140)
print()

# Header
print(f"{'#':<4} {'Entry Date':<12} {'Symbol':<14} {'Entry ₹':<10} {'Exit Date':<12} {'Exit ₹':<10} {'Qty':<6} {'Invest ₹':<12} {'Gross P&L':<12} {'Brokerage':<10} {'Net P&L':<12} {'Exit Type':<14} {'Max DD ₹':<10} {'Holding':<8}")
print("-" * 140)

total_invested = 0
total_pnl = 0
total_brokerage = 0
wins = 0
losses = 0
sl_count = 0
trail_count = 0
target_count = 0
max_hold_count = 0
max_single_loss = 0
max_single_loss_symbol = ""
max_single_win = 0
max_single_win_symbol = ""
consecutive_losses = 0
max_consecutive_losses = 0
running_pnl = 0
peak_pnl = 0
max_dd = 0

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
    stop_loss = t.get('stopLoss', 0)
    target = t.get('target', 0)
    holding_days = t.get('holdingDays', 0)
    max_dd_trade = t.get('maxDrawdown', 0)
    
    invest = entry_price * qty if qty > 0 else capital
    
    # Track stats
    total_invested += invest
    total_pnl += pnl
    total_brokerage += brokerage
    
    if net_pnl > 0:
        wins += 1
        if net_pnl > max_single_win:
            max_single_win = net_pnl
            max_single_win_symbol = symbol
    else:
        losses += 1
        if net_pnl < max_single_loss:
            max_single_loss = net_pnl
            max_single_loss_symbol = symbol
    
    if exit_type == 'SL_HIT': sl_count += 1
    elif exit_type == 'TRAIL_SL': trail_count += 1
    elif exit_type == 'TARGET_HIT': target_count += 1
    elif exit_type == 'MAX_HOLD': max_hold_count += 1
    
    running_pnl += net_pnl
    if running_pnl > peak_pnl: peak_pnl = running_pnl
    dd = peak_pnl - running_pnl
    if dd > max_dd: max_dd = dd
    
    if net_pnl < 0:
        consecutive_losses += 1
        if consecutive_losses > max_consecutive_losses:
            max_consecutive_losses = consecutive_losses
    else:
        consecutive_losses = 0
    
    gross_str = f"+₹{pnl:.0f}" if pnl > 0 else f"-₹{abs(pnl):.0f}"
    net_str = f"+₹{net_pnl:.0f}" if net_pnl > 0 else f"-₹{abs(net_pnl):.0f}"
    dd_str = f"₹{abs(max_dd_trade):.0f}" if max_dd_trade else "-"
    hold_str = f"{holding_days}d" if holding_days else "-"
    
    color = "\033[92m" if net_pnl > 0 else "\033[91m"
    reset = "\033[0m"
    
    print(f"{i+1:<4} {entry_date:<12} {symbol:<14} {entry_price:<10.2f} {exit_date:<12} {exit_price:<10.2f} {qty:<6} ₹{invest:<11,.0f} {color}{gross_str:<12}{reset} ₹{brokerage:<9.0f} {color}{net_str:<12}{reset} {exit_type:<14} {dd_str:<10} {hold_str:<8}")

print("-" * 140)
print()

# Summary
print("=" * 80)
print("  SUMMARY STATISTICS")
print("=" * 80)
print(f"  Total Trades:           {len(trades)}")
print(f"  Wins:                   {wins} ({wins/len(trades)*100:.1f}%)")
print(f"  Losses:                 {losses} ({losses/len(trades)*100:.1f}%)")
print(f"  Gross P&L:              ₹{total_pnl:,.0f}")
print(f"  Total Brokerage:        ₹{total_brokerage:,.0f}")
print(f"  Net P&L:                ₹{d.get('netPnL', 0):,.0f}")
print(f"  Profit Factor:          {d.get('profitFactor', 0):.2f}")
print(f"  Max Drawdown:           ₹{max_dd:,.0f}")
print()
print(f"  Avg P&L/Trade:          ₹{d.get('avgPnL', 0):,.0f}")
print(f"  Best Trade:             {max_single_win_symbol} +₹{max_single_win:,.0f}")
print(f"  Worst Trade:            {max_single_loss_symbol} -₹{abs(max_single_loss):,.0f}")
print(f"  Max Consecutive Losses: {max_consecutive_losses}")
print()
print(f"  Exit Breakdown:")
print(f"    Trail SL Hits:        {trail_count} ({trail_count/len(trades)*100:.1f}%)")
print(f"    Stop Loss Hits:       {sl_count} ({sl_count/len(trades)*100:.1f}%)")
print(f"    Target Hits:          {target_count} ({target_count/len(trades)*100:.1f}%)")
print(f"    Max Hold Exits:       {max_hold_count} ({max_hold_count/len(trades)*100:.1f}%)")
print()
print(f"  Monthly Breakdown:")
monthly = {}
for t in trades:
    m = t.get('entryTime', '')[:7]
    if m not in monthly: monthly[m] = {'trades': 0, 'wins': 0, 'pnl': 0}
    monthly[m]['trades'] += 1
    if t.get('netPnl', t.get('pnl', 0)) > 0: monthly[m]['wins'] += 1
    monthly[m]['pnl'] += t.get('netPnl', t.get('pnl', 0))
for m in sorted(monthly.keys()):
    data = monthly[m]
    wr = data['wins']/data['trades']*100 if data['trades'] > 0 else 0
    pnl_str = f"+₹{data['pnl']:,.0f}" if data['pnl'] > 0 else f"-₹{abs(data['pnl']):,.0f}"
    print(f"    {m}: {data['trades']:>3} trades, {wr:>5.1f}% WR, {pnl_str}")

# Check if exits are properly triggered
print()
print("=" * 80)
print("  EXIT VALIDATION")
print("=" * 80)
improper = 0
for t in trades:
    exit_type = t.get('exitType', '')
    if exit_type not in ['TRAIL_SL', 'SL_HIT', 'TARGET_HIT', 'MAX_HOLD']:
        print(f"  WARNING: {t.get('symbol')} on {t.get('entryTime','')[:10]} has unusual exit: {exit_type}")
        improper += 1
if improper == 0:
    print(f"  All {len(trades)} exits properly triggered (TRAIL_SL, SL_HIT, TARGET_HIT, MAX_HOLD)")
else:
    print(f"  {improper} trades have improper exits")

PYEOF
