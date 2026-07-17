#!/usr/bin/env python3
"""Verify corrected parity calculation"""
import math

C = 19.8   # CE price
P = 566.3  # PE price
K = 24550  # Strike
r = 0.065  # Risk-free rate
T = 6/365  # Days to expiry
F = 24024  # Futures price

e_rT = math.exp(r * T)

# WRONG formula (old):
wrong_synthetic = C - P + K * math.exp(-r * T)
print(f"WRONG synthetic: {wrong_synthetic:.1f}")
print(f"WRONG deviation: {wrong_synthetic - F:.1f} pts")

# CORRECT formula (new): F = K + (C-P) * e^(rT)
correct_synthetic = K + (C - P) * e_rT
print(f"\nCORRECT synthetic: {correct_synthetic:.1f}")
print(f"CORRECT deviation: {correct_synthetic - F:.1f} pts")

# P&L at expiry if NIFTY = 24024:
# CE expires worthless: -19.8
# PE expires worthless: +566.3
# FUT: 0
pnl_flat = -C + P  # Wait, this is wrong...
# Actually: Buy CE (-C), Sell PE (+P), Sell FUT (+F at entry, -N at expiry)
# At expiry with N=F=24024:
pnl = -C + P + F - 24024  # = -19.8 + 566.3 + 0 = 546.5... wait

# Let me think again:
# Entry: Buy CE @ C=19.8 (pay 19.8), Sell PE @ P=566.3 (receive 566.3), Sell FUT @ F=24024 (receive 24024 margin)
# At expiry N:
# CE payoff: max(N-K, 0) = max(24024-24550, 0) = 0
# PE payoff: -(max(K-N, 0)) = -(max(24550-24024, 0)) = -526. But we sold PE so we keep premium and lose intrinsic
# Actually: Sell PE means we owe max(K-N, 0) = 526. We received P=566.3, so net on PE = 566.3 - 526 = 40.3
# FUT: Sold at 24024, NIFTY at 24024, so FUT P&L = 0
# Total: -19.8 (CE cost) + 40.3 (PE net) + 0 (FUT) = 20.5

# Hmm wait, let me redo this properly
# Buy CE: cost C, payoff max(N-K, 0)
# Sell PE: receive P, owe max(K-N, 0)
# Sell FUT: enter at F, at expiry worth N, so P&L = F - N

pnl_at_expiry = -C + max(24024-K, 0) + P - max(K-24024, 0) + F - 24024
print(f"\nP&L at expiry (N={F}): {pnl_at_expiry:.1f} pts")
print(f"Per lot (x50): Rs.{pnl_at_expiry * 50:.0f}")

# STT costs for round trip
stt_sell_pe = P * 0.001 * 50  # 0.1% on sell PE
stt_sell_fut = F * 0.0002 * 50  # 0.02% on sell FUT
stt_sell_ce = C * 0.001 * 50  # 0.1% on sell CE (exit)
stt_buy_fut = F * 0.0002 * 50  # 0.02% on buy FUT (exit)
total_stt = stt_sell_pe + stt_sell_fut + stt_sell_ce + stt_buy_fut
brokerage = 20 * 6
exchange = (P*50 + F*50) * 0.0000345 * 2
sebi = (P*50 + F*50) * 0.000001 * 2
gst = (brokerage + exchange) * 0.18
total_costs = total_stt + brokerage + exchange + sebi + gst

print(f"\n--- COST BREAKDOWN ---")
print(f"STT sell PE: Rs.{stt_sell_pe:.0f}")
print(f"STT sell FUT: Rs.{stt_sell_fut:.0f}")
print(f"STT sell CE (exit): Rs.{stt_sell_ce:.0f}")
print(f"STT buy FUT (exit): Rs.{stt_buy_fut:.0f}")
print(f"Total STT: Rs.{total_stt:.0f}")
print(f"Brokerage (6 orders): Rs.{brokerage:.0f}")
print(f"Exchange charges: Rs.{exchange:.0f}")
print(f"SEBI charges: Rs.{sebi:.0f}")
print(f"GST: Rs.{gst:.0f}")
print(f"TOTAL COSTS: Rs.{total_costs:.0f}")
print(f"\nGross P&L: Rs.{pnl_at_expiry * 50:.0f}")
print(f"Net P&L: Rs.{pnl_at_expiry * 50 - total_costs:.0f}")
