#!/bin/bash
echo "=== Container status ==="
docker ps --filter name=stokr-api --format '{{.ID}} {{.Status}} {{.CreatedAt}}'

echo ""
echo "=== Recent logs (last 2 min) ==="
docker logs --since 2m stokr-api 2>&1 | grep -E "(ERROR|FATAL|password|Started|Tomcat)" | tail -20

echo ""
echo "=== Testing localhost:8080 ==="
docker exec stokr-api curl -s -o /dev/null -w "HTTP_CODE: %{http_code}\n" http://localhost:8080/api/strategies/catalog 2>&1

echo ""
echo "=== Check listening ports ==="
docker exec stokr-api ss -tlnp 2>/dev/null
