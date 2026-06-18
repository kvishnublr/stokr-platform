#!/bin/bash
echo "=== Fixing PostgreSQL Password ==="
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'stokr';"
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr_user WITH PASSWORD 'stokr';"

echo ""
echo "=== Verifying Connection ==="
docker exec -e PGPASSWORD=stokr stokr-postgres psql -U stokr -d stokr_platform -c "SELECT 'Connection OK' as status;"

echo ""
echo "=== Restarting stokr-api ==="
docker restart stokr-api
sleep 20

echo ""
echo "=== Checking Startup Logs ==="
docker logs stokr-api --tail 30 2>&1 | grep -E 'HikariPool.*Started|Started.*Application|Tomcat started|FATAL|ERROR' | tail -10
