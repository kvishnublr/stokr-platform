#!/usr/bin/env python3
"""
Comprehensive patch for stokr option arb execution safety.
Patches the running JAR in-place with:
1. Margin check before order placement
2. Fill status verification (poll Kite after placing orders)
3. Partial fill square-off (exit filled legs if any leg fails)
4. Fix closeAllTrades to work without literal "ALL"
5. Add toMap() to OptionArbOpportunity
6. Add findAllOpen to ExecutedTradeRepository
"""
import os, subprocess, shutil, re, sys

JAR = "/tmp/app-working.jar"
PATCHED_JAR = "/opt/stokr/stokr-platform/stokr-lite/app-patched.jar"
WORK = "/tmp/jar_patch"
CFR_JAR = "/tmp/cfr-0.152.jar"
SRC = "/tmp/jar_src"
CLASSES = "/tmp/jar_classes"

def run(cmd, **kw):
    r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, **kw)
    return r

# Download CFR
if not os.path.exists(CFR_JAR):
    print("Downloading CFR...")
    run(f"wget -q -O {CFR_JAR} https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar")

# Extract JAR
if os.path.exists(WORK):
    shutil.rmtree(WORK)
os.makedirs(WORK)
os.makedirs(SRC, exist_ok=True)
os.makedirs(CLASSES, exist_ok=True)
run(f"cd {WORK} && jar xf {JAR}")
print("JAR extracted")

# Find the classpath (Spring Boot fat JAR libs)
libs = os.path.join(WORK, "BOOT-INF/lib")
cp_files = []
if os.path.isdir(libs):
    for f in os.listdir(libs):
        cp_files.append(os.path.join(libs, f))
cp = os.path.join(WORK, "BOOT-INF/classes") + ":" + ":".join(cp_files)

# Decompile key classes
classes_to_patch = {
    "com/stokr/broker/ZerodhaAdapter": "BOOT-INF/classes/com/stokr/broker/ZerodhaAdapter.class",
    "com/stokr/broker/BrokerOrderResponse": "BOOT-INF/classes/com/stokr/broker/BrokerOrderResponse.class",
    "com/stokr/arbitrage/OptionArbExecutionService": "BOOT-INF/classes/com/stokr/arbitrage/OptionArbExecutionService.class",
    "com/stokr/arbitrage/OptionArbAutoExecuteService": "BOOT-INF/classes/com/stokr/arbitrage/OptionArbAutoExecuteService.class",
    "com/stokr/arbitrage/OptionArbitrageController": "BOOT-INF/classes/com/stokr/arbitrage/OptionArbitrageController.class",
    "com/stokr/arbitrage/ExecutedTradeRepository": "BOOT-INF/classes/com/stokr/arbitrage/ExecutedTradeRepository.class",
    "com/stokr/arbitrage/OptionArbOpportunity": "BOOT-INF/classes/com/stokr/arbitrage/OptionArbOpportunity.class",
    "com/stokr/arbitrage/OptionArbHistoryService": "BOOT-INF/classes/com/stokr/arbitrage/OptionArbHistoryService.class",
    "com/stokr/arbitrage/OptionArbOpportunityRepository": "BOOT-INF/classes/com/stokr/arbitrage/OptionArbOpportunityRepository.class",
}

for cls_name, cls_file in classes_to_patch.items():
    cls_path = os.path.join(WORK, cls_file)
    if not os.path.exists(cls_path):
        print(f"  SKIP: {cls_name} not in JAR")
        continue
    java_path = os.path.join(SRC, cls_name + ".java")
    os.makedirs(os.path.dirname(java_path), exist_ok=True)
    r = run(f"java -jar {CFR_JAR} {cls_path} --outputdir {SRC} --silent false")
    if os.path.exists(java_path):
        print(f"  Decompiled: {cls_name}")
    else:
        print(f"  FAILED: {cls_name}")

print("\nDecompilation complete. Now applying patches...")
