#!/bin/bash
for i in $(seq 1 30); do
  st=$(docker inspect -f '{{.State.Health.Status}}' stokr-api 2>/dev/null || echo missing)
  echo "api=$st"
  [ "$st" = healthy ] && break
  sleep 10
done
cd /opt/stokr/stokr-platform
docker compose --profile app up -d --no-deps ui
for i in $(seq 1 12); do
  st=$(docker inspect -f '{{.State.Health.Status}}' stokr-ui 2>/dev/null || echo missing)
  echo "ui=$st"
  [ "$st" = healthy ] && break
  sleep 5
done
curl -sS -o /dev/null -w 'https=%{http_code}\n' https://stokr.in/
curl -sS -o /dev/null -w 'ui3000=%{http_code}\n' http://127.0.0.1:3000/
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'ui|api|caddy'
