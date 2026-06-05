#!/bin/bash
set -e

NEW_DB_PASS="33Alu8vwlQpQPMuukjEj9SLrUx14D6PEWSIxga47jSI="
NEW_JWT="GnSHTXFjIpTe7RU64b2HaiGOWKvABNW7ZZ281or70C0="
ENV_FILE="/opt/stokr/stokr-platform/.env"

echo "=== Change root password ==="
echo root:19119e3a6793dde1 | chpasswd -e 2>/dev/null || echo root:19119e3a6793dde1 | chpasswd
echo "Root password changed"

echo "=== Change DB password ==="
DB_CONT=$(docker ps -q -f name=postgres 2>/dev/null || docker ps -q -f name=db 2>/dev/null)
if [ -n "$DB_CONT" ]; then
  docker exec -i "$DB_CONT" psql -U postgres -c "ALTER USER postgres PASSWORD '${NEW_DB_PASS}'"
  echo "DB password changed"
else
  echo "WARN: No postgres container found"
fi

echo "=== Update .env ==="
if [ -f "$ENV_FILE" ]; then
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=${NEW_DB_PASS}|" "$ENV_FILE"
  sed -i "s|^SPRING_DATASOURCE_PASSWORD=.*|SPRING_DATASOURCE_PASSWORD=${NEW_DB_PASS}|" "$ENV_FILE"
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=${NEW_JWT}|" "$ENV_FILE"
  echo ".env updated"
  grep -E "^DB_PASSWORD|^JWT_SECRET" "$ENV_FILE"
else
  echo "WARN: .env not found"
fi

echo "=== Restart API containers ==="
docker compose -f /opt/stokr/stokr-platform/docker-compose.yml restart api bootstrap 2>&1 || echo "restart attempted"
echo "Done"
