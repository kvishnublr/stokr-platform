#!/bin/bash
set -e

NEW_DB_PASS="33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
NEW_JWT="GnSHTXFjIpTe7RU64b2HaiGOWKvABNW7ZZ281or70C0="
NEW_SSH="19119e3a6793dde1"
ENV_FILE="/opt/stokr/stokr-platform/.env"

echo "=== 1. Change root password ==="
echo "root:${NEW_SSH}" | chpasswd
echo "Root password changed"

echo "=== 2. Change DB password ==="
PGPASSWORD="newpreview123" psql -U postgres -c "ALTER USER postgres PASSWORD '${NEW_DB_PASS}'"
echo "DB password changed"

echo "=== 3. Update .env ==="
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${NEW_DB_PASS}|" "$ENV_FILE"
sed -i "s|^SPRING_DATASOURCE_PASSWORD=.*|SPRING_DATASOURCE_PASSWORD=${NEW_DB_PASS}|" "$ENV_FILE"
sed -i "s|^JWT_SECRET=.*|JWT_SECRET=${NEW_JWT}|" "$ENV_FILE"
echo ".env updated"

echo "=== 4. Verify .env ==="
grep -E "DB_PASSWORD|JWT_SECRET" "$ENV_FILE"

echo "=== Done ==="