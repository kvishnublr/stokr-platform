#!/usr/bin/env python3
"""Fix: wrong variable names in getSpotAndFutures"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(path, 'r') as f:
    content = f.read()

content = content.replace('.queryParam("i", spotInstrument)', '.queryParam("i", spotKey)')
content = content.replace('.queryParam("i", futInstrument)', '.queryParam("i", futuresKey)')

with open(path, 'w') as f:
    f.write(content)
print("Fixed variable names")
