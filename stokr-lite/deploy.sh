#!/bin/bash
set -e

echo "=== Stokr Lite Deployment ==="
cd /root/stokr-lite

# 1. Setup database
echo "[1/5] Setting up database..."
docker exec stokr-postgres psql -U postgres -c "CREATE DATABASE stokr_lite;" 2>/dev/null || echo "  DB already exists"
docker exec stokr-postgres psql -U postgres -c "ALTER USER stokr WITH PASSWORD 'root123';" 2>/dev/null

# 2. Copy frontend dist to Spring Boot static resources
echo "[2/5] Setting up frontend static files..."
mkdir -p backend/src/main/resources/static
cp -r frontend/dist/* backend/src/main/resources/static/

# 3. Build backend
echo "[3/5] Building backend (this may take a few minutes)..."
cd backend
mvn package -DskipTests -q 2>&1
echo "  Build complete."

# 4. Kill any existing stokr-lite process
echo "[4/5] Stopping previous instance..."
pkill -f 'stokr-lite.*server.port=8081' 2>/dev/null || true
sleep 2

# 5. Run the application
echo "[5/5] Starting stokr-lite on port 8081..."
nohup java -jar target/stokr-lite-0.0.1-SNAPSHOT.jar \
  --server.port=8081 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_lite \
  --spring.datasource.username=stokr \
  --spring.datasource.password=root123 \
  --jwt.secret=stokr-lite-production-secret-key-that-is-at-least-256-bits-long \
  > /root/stokr-lite/app.log 2>&1 &

APP_PID=$!
echo "  Started with PID: $APP_PID"

# Wait for startup
echo "Waiting for application to start..."
for i in $(seq 1 30); do
  if curl -s http://localhost:8081/actuator/health > /dev/null 2>&1; then
    echo "  Application is UP!"
    curl -s http://localhost:8081/actuator/health
    echo ""
    echo "=== Deployment complete ==="
    echo "  Backend:  http://173.249.55.84:8081"
    echo "  Frontend: http://173.249.55.84:8081 (served by Spring Boot)"
    exit 0
  fi
  sleep 2
done

echo "  Application may still be starting. Check logs: tail -f /root/stokr-lite/app.log"
tail -20 /root/stokr-lite/app.log
