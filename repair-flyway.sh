#!/bin/bash
set -e

echo "=== Repairing Flyway ==="
cd /root/stokr-lite/backend

# Repair flyway schema history
mvn flyway:repair -Dflyway.url=jdbc:postgresql://localhost:5432/stokr_lite -Dflyway.user=stokr -Dflyway.password=root123 -q 2>/dev/null || echo "Maven flyway repair attempted"

# If maven repair fails, manually fix the checksum in the database
echo "=== Manual DB repair ==="
docker exec stokr-postgres psql -U stokr -d stokr_lite -c "UPDATE flyway_schema_history SET checksum = -1332135271 WHERE version = '3' AND checksum = 1973628154;"

echo "=== Current flyway history ==="
docker exec stokr-postgres psql -U stokr -d stokr_lite -c "SELECT version, description, checksum, success FROM flyway_schema_history ORDER BY version;"

echo "=== Restarting backend ==="
pkill -f 'stokr-lite.*8070' 2>/dev/null || true
sleep 3

JAR=$(ls target/stokr-lite-*.jar 2>/dev/null | head -1)
nohup java -jar "$JAR" \
  --server.port=8070 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_lite \
  --spring.datasource.username=stokr \
  --spring.datasource.password=root123 \
  --jwt.secret=stokr-lite-production-secret-key-that-is-at-least-256-bits-long \
  > /root/stokr-lite/app.log 2>&1 &

PID=$!
echo "Started PID: $PID"
sleep 5

echo "=== Waiting for health ==="
for i in $(seq 1 45); do
  HEALTH=$(curl -s http://localhost:8070/actuator/health 2>/dev/null || echo "DOWN")
  if echo "$HEALTH" | grep -q "UP"; then
    echo "Backend is UP! Health: $HEALTH"
    echo "=== Testing stokr.in ==="
    curl -s -o /dev/null -w "stokr.in HTTP: HTTP %{http_code}\n" -L http://localhost:80/ -H "Host: stokr.in"
    echo "=== Done ==="
    exit 0
  fi
  if ! kill -0 $PID 2>/dev/null; then
    echo "Process died!"
    tail -30 /root/stokr-lite/app.log
    exit 1
  fi
  sleep 2
done

echo "Timeout. Last logs:"
tail -30 /root/stokr-lite/app.log
exit 1
