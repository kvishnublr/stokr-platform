import math

# User's screenshot trade
strike = 24650
ce = 18.4
pe = 478.7
fut = 24210
spot = 24201.45
lot = 65

# Parity deviation
synthetic = strike + (ce - pe) * math.exp(0.065 * 6/365)
deviation = synthetic - fut
print(f"Synthetic: {synthetic:.1f}")
print(f"Deviation: {deviation:.1f} pts")
print(f"Gross edge: {abs(deviation) * lot:.0f}")

# CURRENT CODE (has double-counted FUT STT)
sttRate = 0.001
sttFutRate = 0.0002
brokeragePerOrder = 20
exchangePct = 0.0000345
sebiPct = 0.000001

sttEntry_curr = pe * sttRate * lot + fut * sttFutRate * lot
sttExit_curr = ce * sttRate * lot + fut * sttFutRate * lot
totalSTT_curr = sttEntry_curr + sttExit_curr
totalBrokerage = brokeragePerOrder * 6
turnover = (ce + pe + fut) * lot * 2
totalExchange = turnover * exchangePct
totalSebi = turnover * sebiPct
totalGst = (totalBrokerage + totalExchange) * 0.18
totalCosts_curr = totalSTT_curr + totalBrokerage + totalExchange + totalSebi + totalGst
edge_curr = abs(deviation) * lot - totalCosts_curr

print(f"\n=== CURRENT CODE (double-counted FUT STT) ===")
print(f"STT: {totalSTT_curr:.2f} (PE={sttEntry_curr:.2f} + CE={sttExit_curr:.2f})")
print(f"Brokerage: {totalBrokerage}")
print(f"Exchange: {totalExchange:.2f}")
print(f"SEBI: {totalSebi:.2f}")
print(f"GST: {totalGst:.2f}")
print(f"Total costs: {totalCosts_curr:.2f}")
print(f"Edge: {edge_curr:.0f}")

# FIXED CODE (correct FUT STT - only once)
totalSTT_fixed = (ce + pe) * sttRate * lot + fut * sttFutRate * lot
turnover_fixed = (ce + pe + fut) * lot * 2
totalExchange_fixed = turnover_fixed * exchangePct
totalSebi_fixed = turnover_fixed * sebiPct
totalGst_fixed = (totalBrokerage + totalExchange_fixed) * 0.18
totalCosts_fixed = totalSTT_fixed + totalBrokerage + totalExchange_fixed + totalSebi_fixed + totalGst_fixed
edge_fixed = abs(deviation) * lot - totalCosts_fixed

print(f"\n=== FIXED CODE (correct FUT STT) ===")
print(f"STT: {totalSTT_fixed:.2f}")
print(f"Brokerage: {totalBrokerage}")
print(f"Exchange: {totalExchange_fixed:.2f}")
print(f"SEBI: {totalSebi_fixed:.2f}")
print(f"GST: {totalGst_fixed:.2f}")
print(f"Total costs: {totalCosts_fixed:.2f}")
print(f"Edge: {edge_fixed:.0f}")

# AlgoTest comparison (for their trade: S FUT 24210, S PE 24200@158.8, B CE 24200@149.3)
print(f"\n=== AlgoTest comparison ===")
# Their breakdown: Brokerage=70.8, STT=802.31, Exchange=34.24, SEBI=1.59, GST=6.45, IPFT=1.67, Stamp=0.29
# Total: 917.36
# Lot size unknown - let's compute what lot gives STT=802.31
# Round-trip STT: (PE+CE)*0.001*lot + FUT*0.0002*lot + 2*FUT*0.0002*lot?
# Or: (PE+CE)*0.001*lot + FUT*0.0002*lot = lot*(158.8+149.3)*0.001 + lot*24210*0.0002
# = lot * 0.3081 + lot * 4.842 = lot * 5.1501
# For lot=25: 128.75
# For lot=50: 257.5
# For lot=65: 334.75
# For lot=75: 386.25
# None match 802.31
# 
# Maybe STT is calculated on FULL contract value for options too?
# Or maybe AlgoTest includes entry+exit differently
#
# Let's check if exchange charge matches
# With lot=25: turnover = (24210+158.8+149.3)*25 = 612,952.5
# Exchange = 612,952.5 * 0.0000345 = 21.15 -- not 34.24
# With lot=25: exchange rate = 34.24/612952.5 = 0.00005586 = 0.005586% 
# That's ~5.59 bps which is higher than 3.45 bps
print("Checking if exchange rate matches...")
for lot_test in [25, 50, 65]:
    turnover_test = (24210 + 158.8 + 149.3) * lot_test
    exchange_test = turnover_test * exchangePct
    print(f"  lot={lot_test}: turnover={turnover_test:.0f}, exchange={exchange_test:.2f}")
