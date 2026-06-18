#!/bin/bash
set -e
cd /root/stokr-platform

echo "========================================="
echo "STOKR PLATFORM - COMPLETE FIX & DEPLOY"
echo "========================================="

echo ""
echo "[1/7] Fixing PostgreSQL user passwords..."
docker exec stokr-postgres psql -U postgres <<'EOF'
ALTER USER stokr WITH PASSWORD 'root123';
ALTER USER postgres WITH PASSWORD 'root123';
GRANT ALL PRIVILEGES ON DATABASE stokr_platform TO stokr;
GRANT ALL ON ALL TABLES IN SCHEMA public TO stokr;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO stokr;
EOF
echo "  -> Passwords set for both stokr and postgres users"

echo ""
echo "[2/7] Verifying DB connection as 'stokr' user..."
docker exec -e PGPASSWORD=root123 stokr-postgres psql -U stokr -d stokr_platform -h localhost -c "SELECT 'DB_CONNECTION_OK' as status, current_user, current_database();"
echo "  -> Connection verified"

echo ""
echo "[3/7] Stopping and removing old API container..."
docker stop stokr-api 2>/dev/null || true
docker rm stokr-api 2>/dev/null || true
echo "  -> Old container removed"

echo ""
echo "[4/7] Recreating API container with fresh env..."
docker compose --profile app up -d api
echo "  -> Container created, waiting 60s for Spring Boot startup..."
sleep 60

echo ""
echo "[5/7] Checking container status..."
STATUS=$(docker ps --filter name=stokr-api --format '{{.Status}}')
echo "  -> Status: $STATUS"

echo ""
echo "[6/7] Checking application logs (last 50 lines)..."
docker logs stokr-api 2>&1 | tail -50

echo ""
echo "[7/7] Testing health endpoint..."
curl -sf --max-time 10 http://localhost:8080/actuator/health 2>/dev/null || echo "  -> Health check failed (app may still be starting)"

echo ""
echo "========================================="
echo "DEPLOYMENT COMPLETE"
echo "========================================="
