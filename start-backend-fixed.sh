#!/bin/bash
# Proper startup script that loads .env and passes all broker configs
set -e

cd /root/stokr-lite

# Load .env
set -a
source /root/stokr-lite/.env
set +a

echo "=== Stopping existing backend ==="
pkill -f 'stokr-lite.*8070' 2>/dev/null || true
sleep 3

cd /root/stokr-lite/backend
JAR=$(ls target/stokr-lite-*.jar | head -1)
echo "=== Starting backend with JAR: $JAR ==="

nohup java -jar "$JAR" \
  --server.port="${SERVER_PORT:-8070}" \
  --spring.datasource.url="jdbc:postgresql://${DB_HOST:-localhost}:${DB_PORT:-5432}/${DB_NAME:-stokr_lite}" \
  --spring.datasource.username="${DB_USERNAME:-stokr}" \
  --spring.datasource.password="${DB_PASSWORD:-root123}" \
  --jwt.secret="${JWT_SECRET:-stokr-lite-production-secret-key-that-is-at-least-256-bits-long}" \
  --stokr.ui.base-url="${STOKR_UI_BASE_URL:-https://stokr.in}" \
  --broker.zerodha.api-key="${ZERODHA_API_KEY:-}" \
  --broker.zerodha.api-secret="${ZERODHA_API_SECRET:-}" \
  --broker.zerodha.redirect-uri="${ZERODHA_REDIRECT_URI:-https://stokr.in/api/brokers/zerodha/callback}" \
  --broker.dhan.api-key="${DHAN_API_KEY:-}" \
  --broker.dhan.redirect-uri="${DHAN_REDIRECT_URI:-https://stokr.in/api/brokers/dhan/callback}" \
  --broker.fyers.api-key="${FYERS_API_KEY:-}" \
  --broker.fyers.redirect-uri="${FYERS_REDIRECT_URI:-https://stokr.in/api/brokers/fyers/callback}" \
  > /root/stokr-lite/app.log 2>&1 &

PID=$!
echo "Started PID: $PID"
sleep 5

echo "=== Waiting for health ==="
for i in $(seq 1 45); do
  HEALTH=$(curl -s http://localhost:8070/actuator/health 2>/dev/null || echo "DOWN")
  if echo "$HEALTH" | grep -q "UP"; then
    echo "Backend is UP!"
    echo "Health: $HEALTH"
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
