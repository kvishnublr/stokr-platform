#!/bin/bash
python3 -c 'import json; json.dump({"username":"admin","password":"admin123"}, open("/tmp/login.json","w"))'
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d @/tmp/login.json | python3 -c '
import sys, json
data = json.load(sys.stdin)
print("Token present:", "accessToken" in data)
if "accessToken" in data:
    print("Token:", data["accessToken"][:60] + "...")
else:
    print("Keys:", list(data.keys()))
    print(data)
'
