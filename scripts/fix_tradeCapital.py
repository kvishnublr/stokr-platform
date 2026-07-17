#!/usr/bin/env python3
"""Fix tradeCapital references - need trade.tradeCapital in simulation loop."""

FILE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java"

with open(FILE, "r") as f:
    content = f.read()

# Fix BUY side tracking
content = content.replace(
    "double unrealHighPnl = (c.high().doubleValue() - entryD) / entryD * tradeCapital;",
    "double unrealHighPnl = (c.high().doubleValue() - entryD) / entryD * trade.tradeCapital;"
)
content = content.replace(
    "double unrealLowPnl  = (c.low().doubleValue() - entryD) / entryD * tradeCapital;",
    "double unrealLowPnl  = (c.low().doubleValue() - entryD) / entryD * trade.tradeCapital;"
)

# Fix SELL side tracking
content = content.replace(
    "double unrealHighPnl = (entryD - c.high().doubleValue()) / entryD * tradeCapital;",
    "double unrealHighPnl = (entryD - c.high().doubleValue()) / entryD * trade.tradeCapital;"
)
content = content.replace(
    "double unrealLowPnl  = (entryD - c.low().doubleValue()) / entryD * tradeCapital;",
    "double unrealLowPnl  = (entryD - c.low().doubleValue()) / entryD * trade.tradeCapital;"
)

with open(FILE, "w") as f:
    f.write(content)

# Count occurrences to verify
count_buy = content.count("trade.tradeCapital) * unrealHighPnl")  # wrong approach, just check trade.tradeCapital in context
count_refs = content.count("trade.tradeCapital")
print(f"  Fixed. trade.tradeCapital refs: {count_refs}")

# Verify no bare tradeCapital in tracking lines
import re
bare = re.findall(r'tradeCapital[^.]', content)
# Filter out field declaration and constructor
bare = [b for b in bare if 'double tradeCapital' not in b and 'this.tradeCapital' not in b and 'trade.tradeCapital' not in b]
print(f"  Bare tradeCapital refs (should be 0): {len(bare)}")
