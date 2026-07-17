#!/usr/bin/env python3
"""
Patch the running app.jar using Python:
1. Extract JAR
2. Decompile key classes with CFR
3. Apply targeted patches
4. Compile patched classes
5. Repackage JAR
"""
import os, subprocess, shutil, sys, re

JAR = "/tmp/app-working.jar"
PATCHED = "/tmp/app-patched.jar"
WORK = "/tmp/jar_patch"
CFR = "/tmp/cfr.jar"
MODS = "/tmp/jar_patch_mods"

# Step 1: Download CFR decompiler
if not os.path.exists(CFR):
    subprocess.run(["wget", "-q", "-O", CFR, 
        "https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar"],
        check=True)
    print("Downloaded CFR decompiler")

# Step 2: Extract JAR
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
os.makedirs(MODS)
subprocess.run(["jar", "xf", JAR], cwd=WORK, check=True)
print("Extracted JAR")

# Step 3: Decompile key classes
classes_to_patch = [
    "BOOT-INF/classes/com/stokr/broker/ZerodhaAdapter.class",
    "BOOT-INF/classes/com/stokr/broker/BrokerOrderResponse.class",
    "BOOT-INF/classes/com/stokr/arbitrage/OptionArbExecutionService.class",
    "BOOT-INF/classes/com/stokr/arbitrage/OptionArbAutoExecuteService.class",
    "BOOT-INF/classes/com/stokr/arbitrage/OptionArbitrageController.class",
    "BOOT-INF/classes/com/stokr/arbitrage/ExecutedTradeRepository.class",
    "BOOT-INF/classes/com/stokr/arbitrage/OptionArbOpportunity.class",
    "BOOT-INF/classes/com/stokr/arbitrage/OptionArbHistoryService.class",
    "BOOT-INF/classes/com/stokr/arbitrage/OptionArbOpportunityRepository.class",
]

print("\n--- Decompiling all classes ---")
for cls in classes_to_patch:
    src_name = cls.replace(".class", ".java").replace("BOOT-INF/classes/", "")
    src_path = os.path.join(MODS, src_name)
    os.makedirs(os.path.dirname(src_path), exist_ok=True)
    
    result = subprocess.run(
        ["java", "-jar", CFR, os.path.join(WORK, cls), 
         "--outputdir", MODS, "--silent", "false"],
        capture_output=True, text=True
    )
    if os.path.exists(src_path):
        print(f"  Decompiled: {src_name}")
    else:
        print(f"  FAILED: {src_name}")
        print(f"    stderr: {result.stderr[:200]}")

# List what we got
for root, dirs, files in os.walk(MODS):
    for f in files:
        if f.endswith(".java"):
            rel = os.path.relpath(os.path.join(root, f), MODS)
            print(f"  Source: {rel}")
