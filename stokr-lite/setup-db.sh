#!/bin/bash
set -e

echo "=== Setting up stokr-lite database ==="
docker exec stokr-postgres psql -U postgres -c "CREATE DATABASE stokr_lite;" 2>/dev/null || echo "DB may already exist"
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'root123';"

echo "=== Database setup complete ==="
docker exec stokr-postgres psql -U postgres -c "\l" | grep stokr_lite
