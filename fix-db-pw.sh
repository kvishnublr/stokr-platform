#!/bin/bash
echo "=== Resetting DB password ==="
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'root123';"
echo "=== Testing connection ==="
docker exec stokr-postgres psql -U stokr -d stokr_lite -c "SELECT 1;"
echo "=== Updating .env for stokr.in ==="
sed -i 's|http://173.249.55.84:8082|https://stokr.in|g' /root/stokr-lite/.env
cat /root/stokr-lite/.env | grep -E 'URI|URL|REDIRECT'
