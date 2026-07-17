#!/bin/bash
for u in NIFTY BANKNIFTY MIDCPNIFTY FINNIFTY; do
    echo "--- $u ---"
    grep "$u" /tmp/instruments.csv | grep "FUT" | grep "NFO" | head -1
    echo "Options count: $(grep "$u" /tmp/instruments.csv | grep "OPT" | grep "NFO" | wc -l)"
    echo "Expiries:"
    grep "$u" /tmp/instruments.csv | grep "OPT" | grep "NFO" | cut -d',' -f6 | sort -u
    echo ""
done
