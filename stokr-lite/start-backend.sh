#!/bin/bash
# Stokr Lite Backend Startup Script
# Usage: bash /root/stokr-lite/start-backend.sh

# Load .env file
set -a
source /root/stokr-lite/.env
set +a

# Kill any existing backend
pkill -f 'java.*stokr-lite' 2>/dev/null
sleep 2

cd /root/stokr-lite/backend
nohup java -jar target/stokr-lite-1.0.0-SNAPSHOT.jar \
  --server.port="$SERVER_PORT" \
  --spring.datasource.password="$DB_PASSWORD" \
  > /root/stokr-lite/backend.log 2>&1 &

echo "Backend started on port $SERVER_PORT"
echo "Logs: tail -f /root/stokr-lite/backend.log"
