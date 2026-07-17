#!/usr/bin/env python3
path = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionChainService.java"
with open(path, 'r') as f:
    content = f.read()
content = content.replace('MAX_DTE = 21', 'MAX_DTE = 14')
with open(path, 'w') as f:
    f.write(content)
print("Done: MAX_DTE = 14")
