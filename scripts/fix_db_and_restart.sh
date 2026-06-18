#!/bin/bash
set -e
echo "=== Step 1: Fix DB passwords ==="
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'root123';"
docker exec stokr-postgres psql -U postgres -c "ALTER USER postgres WITH PASSWORD 'root123';"
echo "Passwords set for both stokr and postgres users"

echo "=== Step 2: Verify connection as stokr ==="
docker exec -e PGPASSWORD=root123 stokr-postgres psql -U stokr -d stokr_platform -h localhost -c "SELECT 'CONNECTION_OK' as status;"

echo "=== Step 3: Check docker-compose env_file ==="
cat /root/stokr-platform/docker-compose.yml | grep -A 5 'env_file\|environment' | head -30

echo "=== Step 4: Restart API container ==="
cd /root/stokr-platform
docker compose restart api
echo "Waiting 30s for startup..."
sleep 30

echo "=== Step 5: Check container status ==="
docker ps --filter name=stokr-api --format '{{.Status}}'

echo "=== Step 6: Check logs if still failing ==="
docker logs stokr-api 2>&1 | tail -30
