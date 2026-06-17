#!/bin/bash
set -e

echo "=== Fix DB and Deploy ==="

# Fix database schema
echo "[1/4] Fixing database schema..."
docker exec stokr-postgres psql -U stokr -d stokr_lite -c "ALTER TABLE universe_symbols ALTER COLUMN tick_size TYPE numeric(38,2);" 2>/dev/null || echo "  tick_size fix attempted"

echo "[2/4] Restarting backend..."
pkill -f 'stokr-lite.*8070' 2>/dev/null || true
sleep 3

cd /root/stokr-lite/backend
JAR=$(ls target/stokr-lite-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "  Building JAR..."
  mvn package -DskipTests -q
  JAR=$(ls target/stokr-lite-*.jar | head -1)
fi

echo "  Starting backend with JAR: $JAR"
nohup java -jar "$JAR" \
  --server.port=8070 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_lite \
  --spring.datasource.username=stokr \
  --spring.datasource.password=root123 \
  --jwt.secret=stokr-lite-production-secret-key-that-is-at-least-256-bits-long \
  > /root/stokr-lite/app.log 2>&1 &

sleep 5
echo "  Backend PID: $!"

echo "[3/4] Updating deploy scripts..."
for script in /root/stokr-lite/deploy.sh /root/stokr-lite/redeploy.sh /root/stokr-lite/final-deploy.sh /root/stokr-lite/verify.sh /root/stokr-lite/deploy-server.sh; do
  if [ -f "$script" ]; then
    sed -i 's/173\.249\.55\.84/stokr.in/g' "$script"
    sed -i 's/:8081/:8070/g' "$script"
    echo "  Updated: $script"
  fi
done

echo "[4/4] Verifying..."
for i in $(seq 1 30); do
  HEALTH=$(curl -s http://localhost:8070/actuator/health 2>/dev/null || echo "DOWN")
  if echo "$HEALTH" | grep -q "UP"; then
    echo "  Backend is UP!"
    echo "  Health: $HEALTH"
    echo "  Frontend stokr.in: $(curl -s -o /dev/null -w '%{http_code}' -L http://localhost:80/ -H 'Host: stokr.in')"
    echo "=== Done ==="
    exit 0
  fi
  sleep 2
done

echo "  Backend still DOWN. Check logs:"
tail -20 /root/stokr-lite/app.log
