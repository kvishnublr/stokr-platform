#!/bin/bash
echo "=== Test DB auth ==="
docker exec -i stokr-postgres psql -U postgres -c "SELECT 1 as test"

echo "=== Check pg_hba.conf ==="
docker exec -i stokr-postgres cat /var/lib/postgresql/data/pg_hba.conf 2>/dev/null | grep -v "^#" | grep -v "^$"

echo "=== Reset password ==="
docker exec -i stokr-postgres psql -U postgres -c "ALTER USER postgres PASSWORD '33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI='"

echo "=== Verify via localhost auth ==="
PGPASSWORD=33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI= docker exec -i stokr-postgres psql -U postgres -h localhost -c "SELECT 2 as test"

echo "=== Verify .env ==="
grep DB_PASSWORD /opt/stokr/stokr-platform/.env | head -2

echo "=== Test via API container ==="
docker exec -i stokr-api env | grep -E "DB_PASS|DB_USER|SPRING_DATASOURCE" 2>/dev/null || echo "API not running"
