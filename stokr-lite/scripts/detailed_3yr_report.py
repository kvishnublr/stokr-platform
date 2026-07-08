#!/usr/bin/env python3
"""Detailed 3-year backtest: capital, unrealised DD, realised DD, avg/max profit"""
import urllib.request
import json
import statistics

BASE = "http://localhost:8081/api/backtest/advanced"
STRATEGIES = ["OVERSOLD_BOUNCE", "EMA50_DISTANCE", "THREE_RED_DAYS"]
STRAT_NAMES = {
    "OVERSOLD_BOUNCE": "Oversold Bounce",
    "EMA50_DISTANCE": "EMA50 Distance",
    "THREE_RED_DAYS": "3 Red Days",
}
CAPITAL = 100000
START = "2023-07-10"
END = "2026-07-08"

def run_backtest(strat, universe):
    url = f"{BASE}?strategy={strat}&universe={universe}&capital={CAPITAL}&dateStart={START}&dateEnd={END}&timeframe=daily"
    req = urllib.request.Request(url, method='POST')
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())

def analyze_trades(trades, capital):
    if not trades:
        return None
    
    pnls = [t.get('pnl', 0) for t in trades]
    gross_pnls = [t.get('grossPnl', t.get('pnl', 0)) for t in trades]
    brokerages = [t.get('brokerage', 0) for t in trades]
    entry_prices = [t.get('entryPrice', 0) for t in trades if t.get('entryPrice')]
    exit_prices = [t.get('exitPrice', 0) for t in trades if t.get('exitPrice')]
    entry_times = [t.get('entryTime', '') for t in trades]
    exit_times = [t.get('exitTime', '') for t in trades]
    
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]
    
    # Realised P&L (accounting for brokerage)
    total_pnl = sum(pnls)
    total_brokerage = sum(brokerages)
    total_gross = sum(gross_pnls)
    
    # Per-trade profit % (based on capital)
    profit_pcts = [(p / capital) * 100 for p in pnls]
    
    # Unrealised max drawdown: equity curve peak-to-trough
    equity = capital
    peak = capital
    max_unrealised_dd = 0
    max_unrealised_dd_pct = 0
    equity_curve = [capital]
    for p in pnls:
        equity += p
        equity_curve.append(equity)
        if equity > peak:
            peak = equity
        dd = peak - equity
        dd_pct = (dd / peak) * 100 if peak > 0 else 0
        if dd > max_unrealised_dd:
            max_unrealised_dd = dd
        if dd_pct > max_unrealised_dd_pct:
            max_unrealised_dd_pct = dd_pct
    
    # Realised max drawdown: consecutive loss streak
    max_consec_loss = 0
    current_streak = 0
    max_loss_streak_amount = 0
    current_streak_amount = 0
    for p in pnls:
        if p < 0:
            current_streak += 1
            current_streak_amount += abs(p)
        else:
            if current_streak > max_consec_loss:
                max_consec_loss = current_streak
                max_loss_streak_amount = current_streak_amount
            current_streak = 0
            current_streak_amount = 0
    if current_streak > max_consec_loss:
        max_consec_loss = current_streak
        max_loss_streak_amount = current_streak_amount
    
    # Winning/losing streaks
    max_win_streak = 0
    current_win = 0
    max_loss_streak = 0
    current_loss = 0
    for p in pnls:
        if p > 0:
            current_win += 1
            current_loss = 0
        elif p < 0:
            current_loss += 1
            current_win = 0
        max_win_streak = max(max_win_streak, current_win)
        max_loss_streak = max(max_loss_streak, current_loss)
    
    # Profit per trade stats
    avg_profit = statistics.mean(pnls) if pnls else 0
    median_profit = statistics.median(pnls) if pnls else 0
    std_profit = statistics.stdev(pnls) if len(pnls) > 1 else 0
    
    # Win/loss analysis
    avg_win = statistics.mean(wins) if wins else 0
    avg_loss = statistics.mean(losses) if losses else 0
    
    # Max profit and max loss per trade
    max_profit = max(pnls) if pnls else 0
    max_loss = min(pnls) if pnls else 0
    
    # Max profit as % of capital
    max_profit_pct = (max_profit / capital) * 100
    max_loss_pct = (max_loss / capital) * 100
    avg_profit_pct = (avg_profit / capital) * 100
    
    # Risk:Reward ratio
    risk_reward = abs(avg_win / avg_loss) if avg_loss != 0 else 0
    
    # Monthly returns
    monthly_pnl = {}
    for t in trades:
        et = t.get('entryTime', '')
        if et:
            month = et[:7]  # YYYY-MM
            monthly_pnl[month] = monthly_pnl.get(month, 0) + t.get('pnl', 0)
    monthly_values = list(monthly_pnl.values())
    profitable_months = sum(1 for v in monthly_values if v > 0)
    total_months = len(monthly_values) if monthly_values else 1
    monthly_win_rate = (profitable_months / total_months) * 100
    
    return {
        'total_trades': len(trades),
        'win_count': len(wins),
        'loss_count': len(losses),
        'win_rate': (len(wins) / len(trades)) * 100,
        'total_pnl': total_pnl,
        'total_gross_pnl': total_gross,
        'total_brokerage': total_brokerage,
        'net_pnl': total_pnl,
        'avg_profit_per_trade': avg_profit,
        'median_profit_per_trade': median_profit,
        'std_profit': std_profit,
        'avg_win': avg_win,
        'avg_loss': avg_loss,
        'max_profit_trade': max_profit,
        'max_loss_trade': max_loss,
        'max_profit_pct': max_profit_pct,
        'max_loss_pct': max_loss_pct,
        'avg_profit_pct': avg_profit_pct,
        'risk_reward': risk_reward,
        'max_unrealised_dd': max_unrealised_dd,
        'max_unrealised_dd_pct': max_unrealised_dd_pct,
        'max_consec_losses': max_consec_loss,
        'max_loss_streak_amount': max_loss_streak_amount,
        'max_win_streak': max_win_streak,
        'max_loss_streak': max_loss_streak,
        'profitable_months': profitable_months,
        'total_months': total_months,
        'monthly_win_rate': monthly_win_rate,
        'final_equity': equity_curve[-1],
        'return_pct': ((equity_curve[-1] - capital) / capital) * 100,
        'capital': capital,
    }

for universe in ["NIFTY_50", "NIFTY_100"]:
    print("\n" + "=" * 120)
    print(f"  3-YEAR DETAILED REPORT: {universe}  |  Capital per trade: Rs{CAPITAL:,}  |  {START} to {END}")
    print("=" * 120)
    
    for strat in STRATEGIES:
        d = run_backtest(strat, universe)
        trades = d.get('trades', [])
        a = analyze_trades(trades, CAPITAL)
        name = STRAT_NAMES.get(strat, strat)
        
        if not a:
            print(f"\n  {name}: NO TRADES")
            continue
        
        print(f"\n  {'='*120}")
        print(f"  {name}")
        print(f"  {'='*120}")
        
        print(f"\n  CAPITAL & RETURNS")
        print(f"  {'-'*60}")
        print(f"  Capital per trade:        Rs{a['capital']:>12,}")
        print(f"  Final equity:             Rs{a['final_equity']:>12,.0f}")
        print(f"  Total return:             {a['return_pct']:>+11.1f}%")
        print(f"  Annualized return:        {a['return_pct']/3:>+11.1f}%")
        
        print(f"\n  TRADE STATS")
        print(f"  {'-'*60}")
        print(f"  Total trades:             {a['total_trades']:>12d}")
        print(f"  Winners:                  {a['win_count']:>12d} ({a['win_rate']:.1f}%)")
        print(f"  Losers:                   {a['loss_count']:>12d}")
        print(f"  Max win streak:           {a['max_win_streak']:>12d}")
        print(f"  Max loss streak:          {a['max_loss_streak']:>12d}")
        
        print(f"\n  PROFIT / LOSS PER TRADE")
        print(f"  {'-'*60}")
        print(f"  Avg profit/trade:         Rs{a['avg_profit_per_trade']:>+11,.0f} ({a['avg_profit_pct']:>+.2f}%)")
        print(f"  Median profit/trade:      Rs{a['median_profit_per_trade']:>+11,.0f}")
        print(f"  Std deviation:            Rs{a['std_profit']:>11,.0f}")
        print(f"  Avg win:                  Rs{a['avg_win']:>+11,.0f}")
        print(f"  Avg loss:                 Rs{a['avg_loss']:>+11,.0f}")
        print(f"  Risk:Reward ratio:        {a['risk_reward']:>12.2f}")
        
        print(f"\n  MAX PROFIT & LOSS")
        print(f"  {'-'*60}")
        print(f"  Max profit (single trade): Rs{a['max_profit_trade']:>+10,.0f} ({a['max_profit_pct']:>+.2f}%)")
        print(f"  Max loss (single trade):   Rs{a['max_loss_trade']:>+10,.0f} ({a['max_loss_pct']:>+.2f}%)")
        print(f"  Max loss streak cost:      Rs{a['max_loss_streak_amount']:>+10,.0f}")
        
        print(f"\n  DRAWDOWN")
        print(f"  {'-'*60}")
        print(f"  Unrealised max DD:         Rs{a['max_unrealised_dd']:>10,.0f} ({a['max_unrealised_dd_pct']:>.1f}%)")
        print(f"  Max consec losses:         {a['max_consec_losses']:>12d}")
        
        print(f"\n  BROKERAGE")
        print(f"  {'-'*60}")
        print(f"  Total brokerage:           Rs{a['total_brokerage']:>10,.0f}")
        print(f"  Brokerage per trade:       Rs{a['total_brokerage']/a['total_trades']:>10,.0f}")
        print(f"  Gross PnL:                 Rs{a['total_gross_pnl']:>+10,.0f}")
        print(f"  Net PnL (after brok):      Rs{a['net_pnl']:>+10,.0f}")
        
        print(f"\n  MONTHLY")
        print(f"  {'-'*60}")
        print(f"  Profitable months:         {a['profitable_months']}/{a['total_months']} ({a['monthly_win_rate']:.0f}%)")
