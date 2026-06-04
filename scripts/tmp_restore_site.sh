#!/bin/bash
set -e
cd /opt/stokr/stokr-platform

echo "=== wait api healthy ==="
for i in $(seq 1 24); do
  st=$(docker inspect -f '{{.State.Health.Status}}' stokr-api 2>/dev/null || echo missing)
  echo "api health=$st ($i)"
  if [ "$st" = healthy ]; then break; fi
  sleep 10
done

echo "=== start ui ==="
docker compose --profile app up -d ui

echo "=== wait ui healthy ==="
for i in $(seq 1 12); do
  st=$(docker inspect -f '{{.State.Health.Status}}' stokr-ui 2>/dev/null || echo missing)
  echo "ui health=$st ($i)"
  if [ "$st" = healthy ]; then break; fi
  sleep 5
done

echo "=== curl checks ==="
curl -sS -o /dev/null -w 'https stokr.in code=%{http_code}\n' https://stokr.in/
curl -sS -o /dev/null -w 'ui:3000 code=%{http_code}\n' http://127.0.0.1:3000/ || true
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'stokr-ui|stokr-api|caddy'
