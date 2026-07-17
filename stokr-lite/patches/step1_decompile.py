#!/usr/bin/env python3
"""
One-shot patch: decompile, patch, compile, deploy.
Fixes: margin check, fill verification, partial fill square-off.
"""
import os, subprocess, shutil

WORK = "/tmp/arb_patch"
JAR_SRC = "/tmp/app-working.jar"
JAR_DST = "/opt/stokr/stokr-platform/stokr-lite/app-patched.jar"
CFR = "/tmp/cfr.jar"

def sh(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if r.returncode != 0:
        print(f"  ERR: {cmd[:80]}... => {r.stderr[:200]}")
    return r

# 1. Setup
if os.path.exists(WORK): shutil.rmtree(WORK)
os.makedirs(f"{WORK}/src", exist_ok=True)
os.makedirs(f"{WORK}/out", exist_ok=True)

if not os.path.exists(CFR):
    sh(f"wget -q -O {CFR} https://github.com/leibnitz27/cfr/releases/download/0.152/cfr-0.152.jar")
    print("CFR downloaded")

# 2. Extract JAR
sh(f"cd {WORK} && jar xf {JAR_SRC}")
print("JAR extracted")

# 3. Build classpath
libs = []
for f in os.listdir(f"{WORK}/BOOT-INF/lib"):
    libs.append(f"{WORK}/BOOT-INF/lib/{f}")
cp = f"{WORK}/BOOT-INF/classes:" + ":".join(libs)

# 4. Find the 3 classes we need to patch
target_classes = [
    ("com.stokr.broker.ZerodhaAdapter", f"{WORK}/BOOT-INF/classes/com/stokr/broker/ZerodhaAdapter.class"),
    ("com.stokr.arbitrage.OptionArbExecutionService", f"{WORK}/BOOT-INF/classes/com/stokr/arbitrage/OptionArbExecutionService.class"),
    ("com.stokr.arbitrage.OptionArbAutoExecuteService", f"{WORK}/BOOT-INF/classes/com/stokr/arbitrage/OptionArbAutoExecuteService.class"),
]

for cls_name, cls_file in target_classes:
    java_file = f"{WORK}/src/{cls_name.replace('.', '/')}.java"
    os.makedirs(os.path.dirname(java_file), exist_ok=True)
    sh(f"java -jar {CFR} {cls_file} --outputdir {WORK}/src --silent false")
    exists = os.path.exists(java_file)
    print(f"Decompile {cls_name.split('.')[-1]}: {'OK' if exists else 'FAIL'}")

print("\nDecompilation complete. Files in:")
for cls_name, _ in target_classes:
    jf = f"{WORK}/src/{cls_name.replace('.', '/')}.java"
    if os.path.exists(jf):
        sz = os.path.getsize(jf)
        print(f"  {jf} ({sz} bytes)")
