import sys, json
d = json.load(sys.stdin)
print("total:", d["total"], "open:", d["openCount"], "closed:", d["closedCount"],
      "failed:", d.get("failedCount", 0), "paper:", d.get("paperCount", 0), "live:", d.get("liveCount", 0))
