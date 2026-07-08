#!/usr/bin/env python3
"""Last 1 year monthly profit for MVR, 3DM, MSR — NIFTY_100 on ₹1L"""
import urllib.request
import json
from collections import OrderedDict

BASE = "http://localhost:8081/api/backtest/advanced"
STRATEGIES = ["MICRO_V_REVERSAL", "THREE_DAY_MOMENTUM", "MORNING_SURGE_REVERSAL"]
STRAT_NAMES = {
    "MICRO_V_REVERSAL": "MVR",
    "THREE_DAY_MOMENTUM": "3DM",
    "MORNING_SURGE_REVERSAL": "MSR",
}
CAPITAL = 100000
START = "2025-07-08"
END = "2026-07-08"

def run_backtest(strat):
    url = f"{BASE}?strategy={strat}&universe=NIFTY_100&capital={CAPITAL}&dateStart={START}&dateEnd={END}&timeframe=daily"
    req = urllib.request.Request(url, method='POST')
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())

# Also run NIFTY_50 for these
def run_backtest_50(strat):
    url = f"{BASE}?strategy={strat}&universe=NIFTY_50&capital={CAPITAL}&dateStart={START}&dateEnd={END}&timeframe=daily"
    req = urllib.request.Request(url, method='POST')
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())

print("=" * 100)
print(f"  MVR / 3DM / MSR — LAST 1 YEAR  |  Capital: Rs{CAPITAL:,}  |  {START} to {END}")
print("=" * 100)

for strat in STRATEGIES:
    name = STRAT_NAMES.get(strat, strat)
    
    for universe in ["NIFTY_50", "NIFTY_100"]:
        d = run_backtest_50(strat) if universe == "NIFTY_50" else run_backtest(strat)
        trades = d.get('trades', [])
        tt = d.get('totalTrades', 0)
        wr = d.get('winRate', 0)
        pnl = d.get('netPnL', 0)
        pf = d.get('profitFactor', 0)
        dd = d.get('maxDrawdown', 0)
        
        print(f"\n  {'='*100}")
        print(f"  {name} — {universe}")
        print(f"  {'='*100}")
        
        if tt == 0:
            print(f"  NO TRADES")
            continue
        
        # Group by month
        monthly = OrderedDict()
        for t in trades:
            et = t.get('entryTime', '')
            if et:
                month = et[:7]
                pnl_val = t.get('pnl', 0)
                monthly[month] = monthly.get(month, 0) + pnl_val
        
        # Fill missing months
        all_months = ['2025-07', '2025-08', '2025-09', '2025-10', '2025-11', '2025-12', '2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06', '2026-07']
        for m in all_months:
            if m not in monthly:
                monthly[m] = 0
        
        print(f"\n  Monthly Breakdown:")
        print(f"  {'Month':<12s} {'PnL':>12s} {'Cumulative':>12s}")
        print(f"  {'-'*40}")
        cum = 0
        for m in all_months:
            val = monthly[m]
            cum += val
            sign = "+" if val >= 0 else ""
            print(f"  {m:<12s} {sign}{val:>10,.0f} {sign}{cum:>10,.0f}")
        
        total = sum(monthly.values())
        sign = "+" if total >= 0 else ""
        print(f"  {'-'*40}")
        print(f"  {'ANNUAL':<12s} {sign}{total:>10,.0f}")
        print(f"  {'RETURN':<12s} {(total/CAPITAL)*100:>+9.1f}%")
        
        # Stats
        pnls = [t.get('pnl', 0) for t in trades]
        wins = [p for p in pnls if p > 0]
        losses = [p for p in pnls if p < 0]
        avg_win = sum(wins)/len(wins) if wins else 0
        avg_loss = sum(losses)/len(losses) if losses else 0
        max_profit = max(pnls) if pnls else 0
        max_loss = min(pnls) if pnls else 0
        
        profitable = sum(1 for v in monthly.values() if v > 0)
        losing = sum(1 for v in monthly.values() if v < 0)
        best_m = max(monthly, key=monthly.get)
        worst_m = min(monthly, key=monthly.get)
        
        print(f"\n  Trades: {tt} | WR: {wr}% | PnL: {sign}{total:,.0f} | PF: {pf:.2f} | MaxDD: Rs{dd:,.0f}")
        print(f"  Avg Win: Rs{avg_win:,.0f} | Avg Loss: Rs{avg_loss:,.0f} | Max Profit: Rs{max_profit:,.0f} | Max Loss: Rs{max_loss:,.0f}")
        print(f"  Profitable months: {profitable}/13 | Losing months: {losing}/13")
        print(f"  Best: {best_m} Rs+{monthly[best_m]:,.0f} | Worst: {worst_m} Rs{monthly[worst_m]:,.0f}")
