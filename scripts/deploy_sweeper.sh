#!/bin/bash
cd /opt/stokr/stokr-platform && git pull origin Release_v2
docker compose build api
docker compose up -d api
echo "=== Waiting for health check ==="
sleep 30
docker compose ps api
echo "=== Checking PositionSweeperService loaded ==="
docker logs stokr-api --since=30s 2>&1 | grep -i "sweeper\|SweeperService\|position.sweep" | tail -5
