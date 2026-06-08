#!/bin/bash
cd /opt/stokr/stokr-platform
echo "=== CURRENT VALUE ==="
grep REPLAY .env || grep SEED .env || grep SYNTHETIC .env
echo ""
echo "=== FIXING .env ==="
# Replace or add the setting
if grep -q "STOKR_REPLAY_SEED_SYNTHETIC" .env; then
  sed -i 's/STOKR_REPLAY_SEED_SYNTHETIC=.*/STOKR_REPLAY_SEED_SYNTHETIC=false/' .env
else
  echo "STOKR_REPLAY_SEED_SYNTHETIC=false" >> .env
fi
echo ""
echo "=== VERIFY FIX ==="
grep STOKR_REPLAY_SEED .env
echo ""
echo "=== REBUILD AND RESTART ==="
docker compose build api 2>&1 | tail -5
docker compose up -d api 2>&1
echo ""
echo "=== WAITING FOR HEALTH ==="
for i in $(seq 1 12); do
  sleep 20
  code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 http://localhost:8080/actuator/health 2>/dev/null)
  echo "Attempt $i: HTTP $code"
  if [ "$code" = "200" ]; then
    echo "API IS HEALTHY"
    break
  fi
done
docker ps --format "{{.Names}} {{.Status}}" | grep stokr-api
