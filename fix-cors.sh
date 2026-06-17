#!/bin/bash
set -e

echo "=== Updating CORS config ==="
cd /root/stokr-lite/backend/src/main/java/com/stokr/config

# Backup
 cp SecurityConfig.java SecurityConfig.java.bak

# Update CORS to include stokr.in
 sed -i 's|config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000",|config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000", "https://stokr.in", "http://stokr.in", "https://www.stokr.in",|g' SecurityConfig.java
 sed -i 's|"http://173.249.55.84:8082", "http://173.249.55.84"));|"http://173.249.55.84:8082", "http://173.249.55.84"));|g' SecurityConfig.java

echo "=== Verifying CORS config ==="
grep -A2 'setAllowedOrigins' SecurityConfig.java

echo "=== Building backend JAR ==="
cd /root/stokr-lite/backend
mvn package -DskipTests -q

echo "=== Restarting backend ==="
pkill -f 'stokr-lite.*8070' 2>/dev/null || true
sleep 3

JAR=$(ls target/stokr-lite-*.jar | head -1)
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
    echo "=== Testing CORS login ==="
    curl -s -X POST http://localhost:8070/api/auth/login \
      -H "Content-Type: application/json" \
      -H "Origin: https://stokr.in" \
      -d '{"email":"admin@stokr.in","password":"Admin@123"}' \
      -w "\nHTTP %{http_code}\n"
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
