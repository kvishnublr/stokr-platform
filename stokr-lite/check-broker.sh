#!/bin/bash
BASE=http://localhost:8070
RESP=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@stokr.in","password":"Admin@123"}')
TOKEN=$(echo "$RESP" | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])" 2>/dev/null)
echo "SUPPORTED: $(curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/brokers/supported)"
echo "CONNECT: $(curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/brokers/zerodha/connect)"
echo "ACCOUNTS: $(curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/brokers)"
