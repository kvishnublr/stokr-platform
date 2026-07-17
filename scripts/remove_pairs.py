import re

FILE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java"

with open(FILE, "r") as f:
    content = f.read()

# 1. Remove the PairsTradingService field
content = content.replace("    private final PairsTradingService pairsTradingService;\n", "")

# 2. Remove the /pairs endpoint (lines 384 to just before /pairs-drift)
# Find the /pairs endpoint
pairs_start = content.find('    @PostMapping("/pairs")')
pairs_drift_start = content.find('    @PostMapping("/pairs-drift")')

if pairs_start >= 0 and pairs_drift_start >= 0:
    # Remove from @PostMapping("/pairs") to just before @PostMapping("/pairs-drift")
    content = content[:pairs_start] + content[pairs_drift_start:]
    print(f"Removed /pairs endpoint ({pairs_drift_start - pairs_start} chars)")

# 3. Now remove the /pairs-drift endpoint (find it again after removal)
pairs_drift_start = content.find('    @PostMapping("/pairs-drift")')
swing_start = content.find('    @PostMapping("/swing")')

if pairs_drift_start >= 0 and swing_start >= 0:
    content = content[:pairs_drift_start] + content[swing_start:]
    print(f"Removed /pairs-drift endpoint ({swing_start - pairs_drift_start} chars)")

# 4. Remove the import if present
content = content.replace("import com.stokr.engine.PairsTradingService;\n", "")

with open(FILE, "w") as f:
    f.write(content)

print("BacktestController cleaned - no more PairsTrading references")
