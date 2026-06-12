#!/bin/bash
cd /opt/stokr/stokr-platform
git pull origin Release_v2
docker compose build --no-cache api
docker compose up -d api
echo "Waiting 60s for startup..."
sleep 60
curl -s -o /dev/null -w "Health: HTTP %{http_code}\n" --connect-timeout 5 http://127.0.0.1:8080/actuator/health
echo "---"
docker logs stokr-api --since=30s 2>&1 | grep "SweeperService\|Started Stokr\|outcome_comment" | tail -5
