#!/usr/bin/env python3
"""Add LocalDate import to controller"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(f) as fp:
    code = fp.read()

code = code.replace(
    "import java.util.*;",
    "import java.time.LocalDate;\nimport java.util.*;"
)

with open(f, 'w') as fp:
    fp.write(code)
print("Import added")
