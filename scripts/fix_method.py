#!/usr/bin/env python3
"""Fix calculateParityEdge method - update signature and body"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionChainService.java"
with open(f) as fp:
    lines = fp.readlines()

# Find and replace the method from line 293
start = None
end = None
for i, line in enumerate(lines):
    if 'private double calculateParityEdge' in line and 'double parityDev, String underlying)' in line:
        start = i
    if start is not None and i > start and line.strip().startswith('return grossEdge'):
        end = i + 1
        break

if start is None or end is None:
    print(f"Could not find method bounds: start={start}, end={end}")
    exit(1)

print(f"Replacing lines {start+1} to {end}")

new_method = """    private double calculateParityEdge(double parityDev, String underlying, double cePrice, double pePrice, double futuresPrice) {
        double rawEdge = Math.abs(parityDev);
        int lotSize = "BANKNIFTY".equals(underlying) ? 15 : 50;
        double grossEdge = rawEdge * lotSize;

        double sttRate = 0.001;
        double sttFutRate = 0.0002;
        double brokeragePerOrder = 20;
        double exchangePct = 0.0000345;
        double sebiPct = 0.000001;

        double sttEntry = pePrice * sttRate * lotSize + futuresPrice * sttFutRate * lotSize;
        double sttExit = cePrice * sttRate * lotSize + futuresPrice * sttFutRate * lotSize;
        double totalSTT = sttEntry + sttExit;

        double totalBrokerage = brokeragePerOrder * 6;

        double turnover = (cePrice + pePrice + futuresPrice) * lotSize * 2;
        double totalExchange = turnover * exchangePct;
        double totalSebi = turnover * sebiPct;
        double totalGst = (totalBrokerage + totalExchange) * 0.18;

        double totalCosts = totalSTT + totalBrokerage + totalExchange + totalSebi + totalGst;
        return grossEdge - totalCosts;
    }
"""

lines[start:end] = [new_method]

with open(f, 'w') as fp:
    fp.writelines(lines)
print("Method replaced successfully")
