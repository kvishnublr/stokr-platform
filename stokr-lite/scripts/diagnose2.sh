#!/bin/bash
set -e
echo "=== Frontend bundle sizes ==="
cd /tmp/jar_extract/BOOT-INF/classes/static
find . -name "*.js" -o -name "*.css" | while read f; do
    size=$(wc -c < "$f")
    echo "${size} ${f}"
done | sort -rn | head -20
echo ""
echo "=== Total frontend size ==="
du -sh /tmp/jar_extract/BOOT-INF/classes/static/
echo ""
echo "=== DB sizes ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT 'candle_data', COUNT(*), pg_size_pretty(pg_total_relation_size('candle_data')) FROM candle_data WHERE timeframe='daily';"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT 'candle_data_1min', COUNT(*), pg_size_pretty(pg_total_relation_size('candle_data')) FROM candle_data WHERE timeframe='1min';"
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT 'trades', COUNT(*), pg_size_pretty(pg_total_relation_size('trades')) FROM trades;"
echo ""
echo "=== tmp_candle tables (cleanup needed?) ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_catalog.pg_statio_user_tables WHERE relname LIKE 'tmp_candle%' ORDER BY pg_total_relation_size(relid) DESC;"
echo ""
echo "=== DB total size ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -t -A -c "SELECT pg_size_pretty(pg_database_size('stokr_lite'));"
echo ""
echo "=== ZERODHA_ACCESS_TOKEN in env ==="
cat /opt/stokr/stokr-lite.env | grep -i ACCESS
echo ""
echo "=== Check env file for what's set ==="
cat /opt/stokr/stokr-lite.env | grep -v '^#' | grep -v '^$'
