#!/bin/bash
curl -s http://localhost:8081/api/strategies | python3 -c '
import sys,json
data=json.load(sys.stdin)
for s in data:
    print(f"{s[\"id\"]:3d} {s[\"strategyType\"]:30s} {s[\"name\"]:30s} {s[\"enabled\"]}")
' | sort -n
