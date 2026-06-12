#!/bin/bash
echo "=== API CONTAINER STATUS ==="
docker ps --format "{{.Names}} {{.Status}}" | grep stokr-api

echo ""
echo "=== TRYING HEALTH ENDPOINTS ==="
for ep in /api/health /actuator/health /health /api/actuator/health; do
  code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "http://localhost:8080$ep" 2>/dev/null)
  echo "$ep -> HTTP $code"
done

echo ""
echo "=== API LOGS (last 20) ==="
docker logs stokr-api --tail 20 2>&1

echo ""
echo "=== API START TIME ==="
docker inspect stokr-api --format '{{.State.StartedAt}}'
