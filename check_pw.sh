#!/bin/bash
echo "=== Password hash for stokr ==="
docker exec stokr-postgres psql -U postgres -c "SELECT usename, passwd FROM pg_shadow WHERE usename='stokr';"

echo ""
echo "=== Fix password with explicit md5 ==="
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'stokr';"

echo ""
echo "=== Verify from another container ==="
docker run --rm --network stokr-platform_default -e PGPASSWORD=stokr postgres:16-alpine psql -h postgres -U stokr -d stokr_platform -c "SELECT 1;"
