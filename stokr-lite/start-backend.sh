#!/bin/bash
set -e
APP_DIR="/opt/stokr-lite"
LOG_FILE="$APP_DIR/app.log"
PID_FILE="$APP_DIR/app.pid"
JAR_FILE="$APP_DIR/app.jar"

# Kill existing process if running
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID"
        sleep 3
    fi
fi

# Clear log
> "$LOG_FILE"

# Start application
export DB_PASSWORD=${DB_PASSWORD:?Set DB_PASSWORD env var}
export DB_HOST=173.249.55.84
export DB_PORT=5432
export DB_NAME=stokr_lite
export DB_USERNAME=stokr
export SERVER_PORT=8070

cd "$APP_DIR"
nohup java -jar "$JAR_FILE" --spring.profiles.active=prod --server.port=8070 > "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
echo "Started stokr-lite with PID $NEW_PID"
sleep 5
ps -p "$NEW_PID" -o pid,comm,args || echo "Process not found!"
