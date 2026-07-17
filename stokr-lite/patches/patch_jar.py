#!/usr/bin/env python3
"""
Patch the running app.jar with critical fixes:
1. Margin check before execution
2. Partial fill square-off
3. Fill verification (poll Kite API)
4. Fix placeOrder returning "COMPLETE" - should return "OPEN"
"""
import os
import subprocess
import sys

JAR_PATH = "/tmp/app-working.jar"
PATCHED_JAR = "/tmp/app-patched.jar"
WORK_DIR = "/tmp/jar_patch"

os.makedirs(WORK_DIR, exist_ok=True)

# Extract the JAR
subprocess.run(["jar", "xf", JAR_PATH], cwd=WORK_DIR, check=True)
print(f"Extracted JAR to {WORK_DIR}")

# Read ZerodhaAdapter.class - patch "COMPLETE" -> "OPEN" in placeOrder
za_path = os.path.join(WORK_DIR, "BOOT-INF/classes/com/stokr/broker/ZerodhaAdapter.class")
with open(za_path, "rb") as f:
    data = f.read()

# The string "COMPLETE" is stored as a constant in the class file
# We need to replace the return value in placeOrder
# Instead, let's patch BrokerOrderResponse.isSuccess() to also treat "OPEN" as success
# Actually, it already does that. The real issue is that the system uses the return 
# status to decide fill status. Let's check what the return is.

# The actual fix needed is in OptionArbAutoExecuteService and OptionArbExecutionService
# to poll for fill status after placing orders. But since we can't recompile...

# Let's verify what the current broker response status looks like
print("Checking BrokerOrderResponse...")
bor_path = os.path.join(WORK_DIR, "BOOT-INF/classes/com/stokr/broker/BrokerOrderResponse.class")
with open(bor_path, "rb") as f:
    bor_data = f.read()
# Check if "COMPLETE" string exists
if b"COMPLETE" in bor_data:
    print("  BrokerOrderResponse contains 'COMPLETE' string")

# Check ZerodhaAdapter for "COMPLETE" 
if b"COMPLETE" in data:
    print("  ZerodhaAdapter contains 'COMPLETE' string")
    # Find its position
    idx = data.index(b"COMPLETE")
    print(f"  'COMPLETE' found at offset {idx}")

print("\nChecking OptionArbExecutionService...")
oaes_path = os.path.join(WORK_DIR, "BOOT-INF/classes/com/stokr/arbitrage/OptionArbExecutionService.class")
with open(oaes_path, "rb") as f:
    oaes_data = f.read()

print("Checking OptionArbAutoExecuteService...")
oaes2_path = os.path.join(WORK_DIR, "BOOT-INF/classes/com/stokr/arbitrage/OptionArbAutoExecuteService.class")
with open(oaes2_path, "rb") as f:
    oaes2_data = f.read()

print("Checking OptionArbitrageController...")
oac_path = os.path.join(WORK_DIR, "BOOT-INF/classes/com/stokr/arbitrage/OptionArbitrageController.class")
with open(oac_path, "rb") as f:
    oac_data = f.read()
    if b"findOpenByUnderlying" in oac_data:
        print("  Controller uses findOpenByUnderlying")
    if b"findAllOpen" in oac_data:
        print("  Controller uses findAllOpen")

# Check ExecutedTradeRepository
etr_path = os.path.join(WORK_DIR, "BOOT-INF/classes/com/stokr/arbitrage/ExecutedTradeRepository.class")
if os.path.exists(etr_path):
    with open(etr_path, "rb") as f:
        etr_data = f.read()
    if b"findAllOpen" in etr_data:
        print("  Repository already has findAllOpen")
    else:
        print("  Repository MISSING findAllOpen")

print("\nAnalysis complete. The current JAR has the OLD code.")
print("We need to write a comprehensive Python patch script instead.")
