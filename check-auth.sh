#!/bin/bash

echo "=== Checking backend logs for auth errors ==="
tail -100 /root/stokr-lite/app.log | grep -i -E "auth|login|error|exception" | tail -30

echo ""
echo "=== Checking users in database ==="
docker exec stokr-postgres psql -U stokr -d stokr_lite -c "SELECT id, email, role, created_at FROM users LIMIT 5;"

echo ""
echo "=== Testing auth endpoint directly ==="
curl -s -X POST http://localhost:8070/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@stokr.in","password":"Admin@123"}' \
  -w "\nHTTP %{http_code}\n"

echo ""
echo "=== Testing auth via nginx ==="
curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@stokr.in","password":"Admin@123"}' \
  -w "\nHTTP %{http_code}\n"
