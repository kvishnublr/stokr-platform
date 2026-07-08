#!/usr/bin/env python3
"""Last 1 year monthly profit breakdown for NIFTY_100 on ₹1L"""
import urllib.request
import json
from collections import OrderedDict

BASE = "http://localhost:8081/api/backtest/advanced"
STRATEGIES = ["OVERSOLD_BOUNCE", "EMA50_DISTANCE", "THREE_RED_DAYS"]
STRAT_NAMES = {
    "OVERSOLD_BOUNCE": "OB",
    "EMA50_DISTANCE": "EMA50D",
    "THREE_RED_DAYS": "TRD",
}
CAPITAL = 100000
START = "2025-07-08"
END = "2026-07-08"

def run_backtest(strat):
    url = f"{BASE}?strategy={strat}&universe=NIFTY_100&capital={CAPITAL}&dateStart={START}&dateEnd={END}&timeframe=daily"
    req = urllib.request.Request(url, method='POST')
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())

print("=" * 100)
print(f"  NIFTY 100 — LAST 1 YEAR MONTHLY BREAKDOWN  |  Capital: Rs{CAPITAL:,}  |  {START} to {END}")
print("=" * 100)

all_monthly = {}
all_annual = {}

for strat in STRATEGIES:
    d = run_backtest(strat)
    trades = d.get('trades', [])
    name = STRAT_NAMES.get(strat, strat)
    
    # Group by month
    monthly = OrderedDict()
    for t in trades:
        et = t.get('entryTime', '')
        if et:
            month = et[:7]
            pnl = t.get('pnl', 0)
            monthly[month] = monthly.get(month, 0) + pnl
    
    # Fill missing months
    all_months = sorted(set(list(monthly.keys()) + ['2025-07', '2025-08', '2025-09', '2025-10', '2025-11', '2025-12', '2026-01', '2026-02', '2026-03', '2026-04', '2026-05', '2026-06', '2026-07']))
    for m in all_months:
        if m not in monthly:
            monthly[m] = 0
    
    all_monthly[name] = monthly
    all_annual[name] = sum(monthly.values())

# Print header
months = list(list(all_monthly.values())[0].keys())
print(f"\n  {'Month':<12s}", end="")
for name in all_monthly:
    print(f"  {name:>10s}", end="")
print(f"  {'TOTAL':>10s}")
print("  " + "-" * (12 + 12 * (len(all_monthly) + 1)))

# Print each month
for m in months:
    print(f"  {m:<12s}", end="")
    month_total = 0
    for name in all_monthly:
        val = all_monthly[name].get(m, 0)
        month_total += val
        color = "+" if val >= 0 else ""
        print(f"  {color}{val:>9,.0f}", end="")
    color = "+" if month_total >= 0 else ""
    print(f"  {color}{month_total:>9,.0f}")

# Print totals
print("  " + "-" * (12 + 12 * (len(all_monthly) + 1)))
print(f"  {'ANNUAL TOTAL':<12s}", end="")
grand_total = 0
for name in all_monthly:
    val = all_annual[name]
    grand_total += val
    print(f"  +{val:>9,.0f}", end="")
print(f"  +{grand_total:>9,.0f}")

# Print return %
print(f"\n  {'RETURN ON ₹1L':<12s}", end="")
for name in all_monthly:
    val = all_annual[name]
    pct = (val / CAPITAL) * 100
    print(f"  {pct:>9.1f}%", end="")
print(f"  {((grand_total / CAPITAL) * 100):>9.1f}%")

# Per-strategy summary
print(f"\n\n  STRATEGY SUMMARY (NIFTY 100, Last 1 Year)")
print("  " + "=" * 80)
for strat in STRATEGIES:
    d = run_backtest(strat)
    trades = d.get('trades', [])
    name = STRAT_NAMES.get(strat, strat)
    tt = d.get('totalTrades', 0)
    wr = d.get('winRate', 0)
    pnl = d.get('netPnL', 0)
    pf = d.get('profitFactor', 0)
    dd = d.get('maxDrawdown', 0)
    
    pnls = [t.get('pnl', 0) for t in trades]
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    avg_win = sum(wins) / len(wins) if wins else 0
    avg_loss = sum(losses) / len(losses) if losses else 0
    max_profit = max(pnls) if pnls else 0
    max_loss = min(pnls) if pnls else 0
    
    print(f"\n  {name}")
    print(f"  Trades: {tt} | WR: {wr}% | PnL: Rs{pnl:,.0f} | PF: {pf:.2f} | MaxDD: Rs{dd:,.0f}")
    print(f"  Avg Win: Rs{avg_win:,.0f} | Avg Loss: Rs{avg_loss:,.0f} | Max Profit: Rs{max_profit:,.0f} | Max Loss: Rs{max_loss:,.0f}")
    
    # Monthly breakdown
    monthly = all_monthly[name]
    profitable = sum(1 for v in monthly.values() if v > 0)
    losing = sum(1 for v in monthly.values() if v < 0)
    best_month = max(monthly.values())
    worst_month = min(monthly.values())
    best_month_name = max(monthly, key=monthly.get)
    worst_month_name = min(monthly, key=monthly.get)
    print(f"  Profitable months: {profitable}/12 | Losing months: {losing}/12")
    print(f"  Best month: {best_month_name} Rs+{best_month:,.0f} | Worst month: {worst_month_name} Rs{worst_month:,.0f}")
