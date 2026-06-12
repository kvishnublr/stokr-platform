#!/bin/bash
cd /opt/stokr/stokr-platform
echo "=== GIT PULL ==="
git pull origin Release_v2 2>&1
echo ""
echo "=== DOCKER BUILD ==="
docker compose build api 2>&1
echo ""
echo "=== DOCKER UP ==="
docker compose up -d api 2>&1
echo ""
echo "=== WAITING FOR HEALTH ==="
for i in $(seq 1 8); do
  sleep 30
  code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 http://localhost:8080/api/health 2>/dev/null)
  echo "Attempt $i: HTTP $code"
  if [ "$code" = "200" ]; then
    echo "API IS HEALTHY"
    break
  fi
done
echo ""
docker ps --format "{{.Names}} {{.Status}}" | grep stokr-api
