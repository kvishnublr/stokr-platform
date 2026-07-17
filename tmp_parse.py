#!/usr/bin/env python3
import json, sys
d = json.load(sys.stdin)
print(json.dumps({"status":d["status"],"total":d["totalOpportunities"],"summary":d.get("summary",{})},indent=2))
