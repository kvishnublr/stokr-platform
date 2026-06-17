#!/bin/bash
docker exec stokr-postgres psql -U stokr -d stokr_lite -c "UPDATE flyway_schema_history SET checksum = -1658699599 WHERE version = '3';"
echo "Checksum updated. Restarting backend..."
pkill -f 'stokr-lite.*8070' 2>/dev/null || true
sleep 3
cd /root/stokr-lite/backend
JAR=$(ls target/stokr-lite-*.jar | head -1)
nohup java -jar "$JAR" \
  --server.port=8070 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_lite \
  --spring.datasource.username=stokr \
  --spring.datasource.password=root123 \
  --jwt.secret=stokr-lite-production-secret-key-that-is-at-least-256-bits-long \
  > /root/stokr-lite/app.log 2>&1 &
echo "PID: $!"
sleep 5
for i in $(seq 1 30); do
  HEALTH=$(curl -s http://localhost:8070/actuator/health 2>/dev/null || echo "DOWN")
  if echo "$HEALTH" | grep -q "UP"; then
    echo "Backend UP! Health: $HEALTH"
    curl -s -X POST http://localhost:8070/api/auth/login -H "Content-Type: application/json" -H "Origin: https://stokr.in" -d '{"email":"admin@stokr.in","password":"Admin@123"}' -w "\nLogin HTTP: %{http_code}\n"
    exit 0
  fi
  sleep 2
done
tail -20 /root/stokr-lite/app.log
