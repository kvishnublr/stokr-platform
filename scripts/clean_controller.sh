#!/bin/bash
# Clean up BacktestController - replace bloated STRATEGY_PLUGIN_MAP with only 5 kept strategies
FILE="/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java"

# Replace the STRATEGY_PLUGIN_MAP block
python3 << 'PYEOF'
import re

with open("/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java", "r") as f:
    content = f.read()

# New clean map with only 5 strategies + aliases
new_map = '''    private static final Map<String, String> STRATEGY_PLUGIN_MAP = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("OVERSOLD_BOUNCE",       "OVERSOLD_BOUNCE"),
        Map.entry("OB",                    "OVERSOLD_BOUNCE"),
        Map.entry("EMA50_DISTANCE",        "EMA50_DISTANCE"),
        Map.entry("EMA50D",                "EMA50_DISTANCE"),
        Map.entry("THREE_RED_DAYS",        "THREE_RED_DAYS"),
        Map.entry("3RD",                   "THREE_RED_DAYS"),
        Map.entry("RSI_OVERSOLD",          "RSI_OVERSOLD"),
        Map.entry("RSIO",                  "RSI_OVERSOLD"),
        Map.entry("MORNING_SURGE_REVERSAL", "MORNING_SURGE_REVERSAL"),
        Map.entry("SURGE_REV",             "MORNING_SURGE_REVERSAL"),
        Map.entry("MSR",                   "MORNING_SURGE_REVERSAL")
    ));'''

# Replace old map
old_map_pattern = r'    private static final Map<String, String> STRATEGY_PLUGIN_MAP = new LinkedHashMap<>\(Map\.ofEntries\(\n.*?\)\);'
content = re.sub(old_map_pattern, new_map, content, flags=re.DOTALL)

# Replace DAILY_STRATEGIES
old_daily = r'    private static final java\.util\.Set<String> DAILY_STRATEGIES = java\.util\.Set\.of\(\n        "OVERSOLD_BOUNCE", "THREE_DAY_MOMENTUM", "EMA50_DISTANCE", "RSI_OVERSOLD", "THREE_RED_DAYS"\n    \);'
new_daily = '''    private static final java.util.Set<String> DAILY_STRATEGIES = java.util.Set.of(
        "OVERSOLD_BOUNCE", "EMA50_DISTANCE", "THREE_RED_DAYS", "RSI_OVERSOLD"
    );'''
content = re.sub(old_daily, new_daily, content)

with open("/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java", "w") as f:
    f.write(content)

print("BacktestController cleaned up successfully")
PYEOF
