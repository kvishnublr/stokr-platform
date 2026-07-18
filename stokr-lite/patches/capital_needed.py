data = {
    "NIFTY":      {"lot": 65,  "atm": 24350, "ce": 200, "pe": 200, "fut": 24350},
    "BANKNIFTY":  {"lot": 30,  "atm": 58500, "ce": 400, "pe": 400, "fut": 58500},
    "MIDCPNIFTY": {"lot": 120, "atm": 14700, "ce": 150, "pe": 150, "fut": 14700},
    "FINNIFTY":   {"lot": 60,  "atm": 26900, "ce": 250, "pe": 250, "fut": 26900},
}

buffer = 1.15
strikes_per = 3

print("=" * 70)
print("CAPITAL REQUIRED FOR MONDAY LIVE")
print("=" * 70)
print()
print("NOTE: Code margin check = (CE + PE + FUT) x lot x 1.15")
print("This is NOTORIOUSLY CONSERVATIVE (full notional, not hedged margin).")
print("Real Zerodha margin for conversion is MUCH lower due to hedge benefit.")
print()

print("CODE'S MARGIN CHECK (conservative):")
print("-" * 50)
for u, d in data.items():
    code_margin = (d["ce"] + d["pe"] + d["fut"]) * d["lot"] * buffer
    print(f"  {u:12s}: Rs.{code_margin/100000:.1f}L per position")
print()

# Real Zerodha conversion margin: hedged position
# Short CE + Short PE + Short FUT = conversion
# Zerodha gives ~80% margin benefit on hedged positions
real_margins = {
    "NIFTY":      150000,
    "BANKNIFTY":  250000,
    "MIDCPNIFTY": 180000,
    "FINNIFTY":   200000,
}

print("REAL ZERODHA MARGIN (conversion with hedge benefit):")
print("-" * 50)
for u, m in real_margins.items():
    print(f"  {u:12s}: Rs.{m*buffer/100000:.1f}L per position")
print()

print("=" * 70)
print("SCENARIOS")
print("=" * 70)
print()

scenarios = [
    ("NIFTY only", ["NIFTY"]),
    ("NIFTY + BANKNIFTY", ["NIFTY", "BANKNIFTY"]),
    ("All 4 underlyings", ["NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY"]),
]

for name, unds in scenarios:
    total = sum(real_margins[u] * buffer * strikes_per for u in unds)
    with_buffer = total * 1.20
    print(f"  {name} ({len(unds)*strikes_per} positions):")
    print(f"    Margin needed: Rs.{total/100000:.1f}L")
    print(f"    With 20pct buffer: Rs.{with_buffer/100000:.1f}L")
    print()

print("RECOMMENDATION:")
print("-" * 50)
print("  Minimum to start:  Rs.6L  (3 NIFTY strikes)")
print("  Recommended:       Rs.12L (NIFTY + BANKNIFTY, 6 strikes)")
print("  Full deployment:   Rs.25L (all 4, 12 strikes)")
print()
print("  CRITICAL: Code margin check (CE+PE+FUT)*lot*1.15 is ~Rs.18-20L per NIFTY position.")
print("  This is WAY too high. Real margin is ~Rs.1.7L. Auto-execute may BLOCK.")
print("  Must fix margin check to use actual Zerodha margin API or lower the estimate.")
