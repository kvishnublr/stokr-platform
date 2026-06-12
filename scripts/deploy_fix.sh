#!/bin/bash
# Remove conflicting untracked files from earlier scp
rm -f /opt/stokr/stokr-platform/scripts/position_sweeper.py
# Now git pull should succeed
cd /opt/stokr/stokr-platform
git pull origin Release_v2
# Force rebuild from scratch to ensure no cache
docker compose build --no-cache api
docker compose up -d api
echo "Waiting 45s for startup..."
sleep 45
docker compose ps api
echo "=== Health check ==="
curl -s -o /dev/null -w "HTTP %{http_code}" --connect-timeout 5 http://127.0.0.1:8080/actuator/health
echo ""
echo "=== PositionSweeperService in logs ==="
docker logs stokr-api --since=30s 2>&1 | grep -i "sweeper\|SweeperService\|position.sweep" | tail -5
echo "=== App startup ==="
docker logs stokr-api --since=60s 2>&1 | grep "Started StokrApplication" | tail -1
