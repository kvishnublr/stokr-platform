#!/bin/bash

echo "=== POSITIONS TABLE SCHEMA ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "\d positions;"

echo ""
echo "=== ORDERS TABLE SCHEMA ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "\d orders;"

echo ""
echo "=== ALL POSITIONS ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT * FROM positions;"

echo ""
echo "=== ALL ORDERS ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT * FROM orders ORDER BY created_at DESC;"

echo ""
echo "=== LTP BATCH ERROR DETAILS ==="
docker logs stokr-lite-backend --tail 300 2>&1 | grep -iB2 -A5 "ltp.*batch\|marketdata\|MarketDataController\|500\|Internal Server" | tail -30

echo ""
echo "=== ALL BACKEND ERRORS (last 500 lines) ==="
docker logs stokr-lite-backend --tail 500 2>&1 | grep -B1 "Exception\|ERROR" | grep -v "at org\.\|at java\.\|at sun\.\|at com.stokr" | tail -20

echo ""
echo "=== POSITIONS TABLE CHECK (Java entity query) ==="
docker logs stokr-lite-backend --tail 500 2>&1 | grep -i "position" | grep -i "error\|column\|does not exist" | tail -10

echo ""
echo "=== JWT TOKENS TABLE CHECK ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name;"

echo ""
echo "=== strategy_universe_mappings CHECK ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "\dt *universe*;"

echo ""
echo "=== ZERODHA_INSTRUMENTS TABLE CHECK ==="
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite -c "\dt *instrument*;"

