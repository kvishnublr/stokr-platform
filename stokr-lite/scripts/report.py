#!/usr/bin/env python3
import json, sys

def print_report(data):
    if 'error' in data:
        print(f"  ERROR: {data['error']}")
        return
    
    trades = data.get('trades', [])
    total_trades = data.get('totalTrades', 0)
    win_rate = data.get('winRate', 0)
    net_pnl = data.get('netPnL', 0)
    pf = data.get('profitFactor', 0)
    max_dd = data.get('maxDrawdown', 0)
    avg_pnl = data.get('avgPnL', 0)
    brokerage = data.get('totalBrokerage', 0)
    gross_pnl = data.get('totalPnL', 0)
    
    print(f"  Strategy:       {data.get('strategy', 'N/A')}")
    print(f"  Universe:       {data.get('universe', 'N/A')}")
    print(f"  Date Range:     {data.get('dateRange', {}).get('start', '')[:10]} to {data.get('dateRange', {}).get('end', '')[:10]}")
    print(f"  Capital:        ₹{data.get('capitalPerTrade', 100000):,.0f}")
    print(f"  ─────────────────────────────────────────")
    print(f"  Total Trades:   {total_trades}")
    print(f"  Win Rate:       {win_rate:.1f}%")
    print(f"  Gross PnL:      ₹{gross_pnl:,.2f}")
    print(f"  Brokerage:      ₹{brokerage:,.2f}")
    print(f"  Net PnL:        ₹{net_pnl:,.2f}")
    print(f"  Profit Factor:  {pf:.2f}")
    print(f"  Max Drawdown:   ₹{max_dd:,.2f}")
    print(f"  Avg PnL/Trade:  ₹{avg_pnl:,.2f}")
    
    if total_trades > 0:
        win_count = data.get('winCount', 0)
        loss_count = data.get('lossCount', 0)
        print(f"  Win/Loss:       {win_count}W / {loss_count}L")
    
    # Exit type breakdown
    exit_types = {}
    for t in trades:
        et = t.get('exitType', 'UNKNOWN')
        exit_types[et] = exit_types.get(et, 0) + 1
    if exit_types:
        print(f"  Exit Breakdown: ", end="")
        print(", ".join(f"{k}:{v}" for k, v in sorted(exit_types.items())))
    
    # Monthly breakdown
    monthly = {}
    for t in trades:
        if t.get('entryTime'):
            month = t['entryTime'][:7]
            if month not in monthly:
                monthly[month] = {'trades': 0, 'wins': 0, 'pnl': 0}
            monthly[month]['trades'] += 1
            if t.get('pnl', 0) > 0:
                monthly[month]['wins'] += 1
            monthly[month]['pnl'] += t.get('pnl', 0) - t.get('brokerage', 0)
    
    if monthly:
        print(f"\n  Monthly Breakdown:")
        print(f"  {'Month':<10} {'Trades':>7} {'Win%':>6} {'Net PnL':>12}")
        print(f"  {'─'*38}")
        for m in sorted(monthly.keys()):
            d = monthly[m]
            wr = d['wins'] / d['trades'] * 100 if d['trades'] > 0 else 0
            print(f"  {m:<10} {d['trades']:>7} {wr:>5.1f}% ₹{d['pnl']:>11,.2f}")
    
    # Top 5 winning trades
    winning = sorted([t for t in trades if t.get('pnl', 0) > 0], key=lambda x: x.get('pnl', 0), reverse=True)[:5]
    if winning:
        print(f"\n  Top 5 Winning Trades:")
        print(f"  {'Symbol':<12} {'Entry':>8} {'Exit':>8} {'PnL':>10} {'Type':<15}")
        print(f"  {'─'*55}")
        for t in winning:
            sym = t.get('symbol', 'N/A')
            entry = t.get('entryPrice', 0)
            exit_p = t.get('exitPrice', 0)
            pnl = t.get('pnl', 0) - t.get('brokerage', 0)
            et = t.get('exitType', 'N/A')
            print(f"  {sym:<12} {entry:>8.2f} {exit_p:>8.2f} ₹{pnl:>9,.2f} {et:<15}")
    
    # Top 5 losing trades
    losing = sorted([t for t in trades if t.get('pnl', 0) <= 0], key=lambda x: x.get('pnl', 0))[:5]
    if losing:
        print(f"\n  Top 5 Losing Trades:")
        print(f"  {'Symbol':<12} {'Entry':>8} {'Exit':>8} {'PnL':>10} {'Type':<15}")
        print(f"  {'─'*55}")
        for t in losing:
            sym = t.get('symbol', 'N/A')
            entry = t.get('entryPrice', 0)
            exit_p = t.get('exitPrice', 0)
            pnl = t.get('pnl', 0) - t.get('brokerage', 0)
            et = t.get('exitType', 'N/A')
            print(f"  {sym:<12} {entry:>8.2f} {exit_p:>8.2f} ₹{pnl:>9,.2f} {et:<15}")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python3 report.py <json_file>")
        sys.exit(1)
    with open(sys.argv[1]) as f:
        data = json.load(f)
    print_report(data)
