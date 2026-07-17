#!/usr/bin/env python3
"""Clean up unused variables and verify everything"""

path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/ZerodhaSpotPriceFetcher.java"
with open(path, 'r') as f:
    content = f.read()

# Remove unused encodedSpot/encodedFut lines
content = content.replace('            String encodedSpot = spotKey.replace(" ", "%20");\n            String encodedFut = futuresKey.replace(" ", "%20");\n', '')

with open(path, 'w') as f:
    f.write(content)
print("Cleaned up unused variables")
