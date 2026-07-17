#!/usr/bin/env python3
"""Fix OptionArbHistoryService to store IST timestamps instead of server-local"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbHistoryService.java"
with open(f) as fp:
    code = fp.read()

# Add ZoneId import
code = code.replace(
    "import java.time.LocalDateTime;",
    "import java.time.LocalDateTime;\nimport java.time.ZoneId;"
)

# Replace all LocalDateTime.now() with LocalDateTime.now(ZoneId.of(\"Asia/Kolkata\"))
code = code.replace("LocalDateTime.now()", "LocalDateTime.now(ZoneId.of(\"Asia/Kolkata\"))")

# But keep the query methods using UTC for date comparisons - those need to use server time or a consistent zone
# Actually the query uses LocalDateTime.now().minusDays() which should also be IST
# Let's leave them as IST too for consistency

with open(f, 'w') as fp:
    fp.write(code)
print("Fixed to IST timestamps")
