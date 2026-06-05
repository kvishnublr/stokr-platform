#!/bin/bash
TOKEN=$(python3 -c '
import urllib.request, json
data = json.dumps({"principal":"admin","password":"admin123"}).encode()
req = urllib.request.Request("http://localhost:8080/api/auth/login", data=data, headers={"Content-Type":"application/json"})
resp = json.load(urllib.request.urlopen(req))
print(resp.get("accessToken", ""))
')
echo "Token obtained: ${TOKEN:0:30}..."
echo ""
echo "=== Broker Infrastructure ==="
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/admin/broker-infrastructure | python3 -m json.tool 2>&1
