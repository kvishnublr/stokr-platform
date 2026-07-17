#!/usr/bin/env python3
FILE = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java"

with open(FILE, "r") as f:
    content = f.read()

# Add rnd2 method before the last closing brace of the class
rnd2_method = '''
    private double rnd2(double v) { return Math.round(v * 100.0) / 100.0; }
'''

# Find last closing brace
last_brace = content.rfind('}')
if last_brace >= 0:
    content = content[:last_brace] + rnd2_method + '}\n'
    with open(FILE, "w") as f:
        f.write(content)
    print("Added rnd2 method")
else:
    print("ERROR: Could not find last closing brace")
