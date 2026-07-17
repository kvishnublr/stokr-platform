import sys

filepath = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java'
with open(filepath, 'r') as f:
    content = f.read()

# Fix CONVERSION: BUY CE first → SELL Futures → SELL PE (margin benefit order)
old_conversion = '''            if ("CONVERSION".equals(action)) {
                orders.add(buildBasketLeg(ceSymbol, "NFO", "BUY", lotSize, cePrice, "NRML"));
                orders.add(buildBasketLeg(peSymbol, "NFO", "SELL", lotSize, pePrice, "NRML"));
                orders.add(buildBasketLeg(futSymbol, "NFO", "SELL", lotSize, futPrice, "NRML"));'''

new_conversion = '''            if ("CONVERSION".equals(action)) {
                orders.add(buildBasketLeg(ceSymbol, "NFO", "BUY", lotSize, cePrice, "NRML"));
                orders.add(buildBasketLeg(futSymbol, "NFO", "SELL", lotSize, futPrice, "NRML"));
                orders.add(buildBasketLeg(peSymbol, "NFO", "SELL", lotSize, pePrice, "NRML"));'''

# Fix REVERSAL: BUY PE first → BUY Futures → SELL CE (margin benefit order)
old_reversal = '''            } else if ("REVERSAL".equals(action)) {
                orders.add(buildBasketLeg(ceSymbol, "NFO", "SELL", lotSize, cePrice, "NRML"));
                orders.add(buildBasketLeg(peSymbol, "NFO", "BUY", lotSize, pePrice, "NRML"));
                orders.add(buildBasketLeg(futSymbol, "NFO", "BUY", lotSize, futPrice, "NRML"));'''

new_reversal = '''            } else if ("REVERSAL".equals(action)) {
                orders.add(buildBasketLeg(peSymbol, "NFO", "BUY", lotSize, pePrice, "NRML"));
                orders.add(buildBasketLeg(futSymbol, "NFO", "BUY", lotSize, futPrice, "NRML"));
                orders.add(buildBasketLeg(ceSymbol, "NFO", "SELL", lotSize, cePrice, "NRML"));'''

count = 0
if old_conversion in content:
    content = content.replace(old_conversion, new_conversion)
    count += 1
if old_reversal in content:
    content = content.replace(old_reversal, new_reversal)
    count += 1

if count == 2:
    with open(filepath, 'w') as f:
        f.write(content)
    print('Both CONVERSION and REVERSAL order fixed')
else:
    print(f'ERROR: only fixed {count}/2')
    sys.exit(1)
