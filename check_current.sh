#!/bin/bash
echo "=== Current container state ==="
docker inspect stokr-api --format '{{.State.Status}} {{.State.Running}} {{.State.StartedAt}} {{.State.FinishedAt}}'

echo ""
echo "=== Uptime ==="
docker ps --filter name=stokr-api --format '{{.Status}}'

echo ""
echo "=== Autoheal logs ==="
docker logs stokr-autoheal --tail 5 2>&1

echo ""
echo "=== API recent logs ==="
docker logs stokr-api --tail 5 2>&1

echo ""
echo "=== Test catalog ==="
docker exec stokr-api curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/strategies/catalog 2>&1
echo ""
