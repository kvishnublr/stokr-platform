import json, sys

data = json.load(sys.stdin)
positions = data['positions']

# Zerodha cost structure for 3-leg box trade (entry + exit = 6 orders)
BROKERAGE_PER_ORDER = 20  # flat ₹20 per order
TOTAL_ORDERS = 6  # 3 enter + 3 exit
BROKERAGE = BROKERAGE_PER_ORDER * TOTAL_ORDERS  # ₹120

# STT: 0.05% on sell side premium, 0.025% on buy side (options)
# Exchange: 0.00345% per side (NSE options)
# SEBI: ₹10 per crore
# GST: 18% on brokerage + exchange + SEBI

def calc_costs(ce, pe, fut, lot_size):
    """Calculate total transaction costs for a 3-leg box trade"""
    # Turnover
    ce_turnover = ce * lot_size * 2  # buy + sell
    pe_turnover = pe * lot_size * 2  # buy + sell
    fut_turnover = fut * lot_size * 2  # buy + sell
    total_turnover = ce_turnover + pe_turnover + fut_turnover
    
    # STT on options premium (sell side = 0.05%, buy side = 0.025%)
    stt = (ce + pe) * lot_size * 0.00075  # 0.05% sell + 0.025% buy = 0.075%
    
    # Exchange charges (0.00345% per side, 6 sides)
    exchange = total_turnover * 0.0000345
    
    # SEBI (₹10 per crore)
    sebi = total_turnover * 0.000001
    
    # GST (18% on brokerage + exchange + SEBI)
    gst = (BROKERAGE + exchange + sebi) * 0.18
    
    total = BROKERAGE + stt + exchange + sebi + gst
    return total

# Analyze trades
real = [p for p in positions if (p.get('pnl') or 0) > 0]

print("=" * 90)
print("ZERODHA BROKERAGE ANALYSIS - 3 LEG BOX TRADE (BUY FUT + SELL CE + SELL PE)")
print("=" * 90)
print(f"\nBrokerage: ₹{BROKERAGE} (6 orders × ₹20)")
print(f"STT: ~0.075% on option premiums (sell 0.05% + buy 0.025%)")
print(f"Exchange: 0.00345% per side")
print(f"SEBI: ₹10 per crore")
print(f"GST: 18% on (brokerage + exchange + SEBI)\n")

print(f"{'Underlying':<12} {'Strike':<8} {'CE':<8} {'PE':<8} {'FUT':<10} {'Lot':<5} {'Costs':<10} {'Profit':<10} {'Net':<10}")
print("-" * 90)

total_costs = 0
total_profit = 0
total_net = 0

for p in real:
    ce = p.get('ceEntryPrice', 0) or 0
    pe = p.get('peEntryPrice', 0) or 0
    fut = p.get('futEntryPrice', 0) or 0
    lot = p.get('lotSize', 25)
    pnl = p.get('pnl', 0)
    
    costs = calc_costs(ce, pe, fut, lot)
    net = pnl - costs
    
    total_costs += costs
    total_profit += pnl
    total_net += net
    
    print(f"{p['underlying']:<12} {p['strike']:<8} {ce:<8.1f} {pe:<8.1f} {fut:<10.1f} {lot:<5} ₹{costs:<9.0f} +₹{pnl:<9.0f} {'+'if net>=0 else ''}₹{net:<9.0f}")

print("-" * 90)
print(f"{'TOTAL':<12} {'':<8} {'':<8} {'':<8} {'':<10} {'':<5} ₹{total_costs:<9.0f} +₹{total_profit:<9.0f} {'+'if total_net>=0 else ''}₹{total_net:<9.0f}")
print(f"\nTotal costs on ₹{total_profit:,.0f} profit: ₹{total_costs:,.0f} ({total_costs/total_profit*100:.1f}% of profit)")
print(f"Net profit after ALL Zerodha charges: ₹{total_net:,.0f}")

# Now compare min edge thresholds
print("\n" + "=" * 90)
print("MIN EDGE COMPARISON (net after ALL costs)")
print("=" * 90)

for min_edge in [0, 100, 200, 300, 500, 700, 1000]:
    filtered = [p for p in real if (p.get('pnl') or 0) >= min_edge]
    if not filtered:
        continue
    f_costs = sum(calc_costs(p.get('ceEntryPrice',0) or 0, p.get('peEntryPrice',0) or 0, p.get('futEntryPrice',0) or 0, p.get('lotSize',25)) for p in filtered)
    f_profit = sum(p['pnl'] for p in filtered)
    f_net = f_profit - f_costs
    avg_net = f_net / len(filtered) if filtered else 0
    
    # Estimate daily (trades per day based on avg hold)
    print(f"  ₹{min_edge:>4} edge: {len(filtered):>2} trades, costs ₹{f_costs:>6.0f}, profit ₹{f_profit:>8.0f}, NET ₹{f_net:>8.0f}, avg ₹{avg_net:>6.0f}/trade")
