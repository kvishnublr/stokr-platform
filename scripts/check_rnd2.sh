#!/bin/bash
FILE="/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java"

# Find all references to rnd2 and surrounding context
grep -n 'rnd2' "$FILE"

echo "---"
# Find the end of the class (last closing brace)
wc -l "$FILE"

echo "---"
# Check if rnd2 is defined anywhere
grep -n 'private.*rnd2\|public.*rnd2\|static.*rnd2\|double rnd2\|def rnd2' "$FILE"
