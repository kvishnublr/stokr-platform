import json
data = json.load(open("/tmp/strats.json"))
for s in data:
    print(s["id"], s["strategyType"], s["name"], s["enabled"])
