#!/bin/bash

# ============================================================
# Stokr Platform - Application Startup Script
# Starts all services: PostgreSQL, Redis, RabbitMQ, API, UI
# ============================================================

set -e

echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         STOKR PLATFORM - APPLICATION STARTUP                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Check Docker daemon
echo "[1/6] Checking Docker daemon..."
if ! docker ps >/dev/null 2>&1; then
    echo "❌ Docker daemon is not running!"
    echo ""
    echo "Please start Docker Desktop and try again:"
    echo "  - Windows: Start Docker Desktop from Start Menu"
    echo "  - Mac: Open Applications > Docker"
    echo "  - Linux: sudo systemctl start docker"
    echo ""
    exit 1
fi
echo "✓ Docker daemon is running"
echo ""

# Navigate to project root
cd "$(dirname "$0")"
PROJECT_ROOT=$(pwd)
echo "[2/6] Project directory: $PROJECT_ROOT"
echo ""

# Check .env file
echo "[3/6] Checking .env configuration..."
if [ ! -f ".env" ]; then
    echo "❌ .env file not found!"
    echo "Creating .env from defaults..."
    cat > .env <<'EOF'
# Database Configuration
DB_NAME=stokr_platform
DB_USER=stokr
DB_PASSWORD=stokr
DB_PORT=5432

# Redis Configuration
REDIS_PORT=6379

# RabbitMQ Configuration
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_PORT=5672
RABBITMQ_UI_PORT=15672

# API Configuration
API_PORT=8080
STOKR_MAIL_HEALTH_ENABLED=false

# UI Configuration
UI_PORT=3000

# Database admin (optional)
PGADMIN_EMAIL=admin@stokr.local
PGADMIN_PASSWORD=admin
PGADMIN_PORT=5050

# Mailpit (optional, for dev email testing)
MAILPIT_SMTP_PORT=1025
MAILPIT_UI_PORT=8025

# JWT Configuration
JWT_SECRET=change-me-use-long-random-secret-for-development-only
JWT_ACCESS_TTL_SECONDS=18000
JWT_REFRESH_TTL_SECONDS=1209600

# Zerodha (optional, for broker integration)
STOKR_ZERODHA_API_KEY=
STOKR_ZERODHA_API_SECRET=
STOKR_ZERODHA_REDIRECT_URL=http://localhost:8080/api/broker/zerodha/callback
STOKR_ZERODHA_TEST_ORDER_ENABLED=false

# UI URLs
STOKR_UI_PUBLIC_BASE_URL=http://localhost:3000
STOKR_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173,http://localhost:5174,http://localhost:5175

# Execution Configuration
STOKR_EXECUTION_MAX_ATTEMPTS=5
STOKR_RISK_ZONE=Asia/Kolkata

# Replay Configuration
STOKR_REPLAY_SEED_SYNTHETIC=true

# RabbitMQ Listeners
STOKR_RABBIT_LISTENERS_ENABLED=true

# GitHub (optional)
STOKR_GITHUB_ENABLED=false
STOKR_GITHUB_TOKEN=
STOKR_GITHUB_OWNER=
STOKR_GITHUB_REPO=
STOKR_GITHUB_BRANCH=main
EOF
    echo "✓ .env file created with defaults"
else
    echo "✓ .env file found"
fi
echo ""

# Stop existing containers if running
echo "[4/6] Cleaning up existing containers..."
docker-compose -f docker-compose.yml down 2>/dev/null || true
echo "✓ Existing containers cleaned up"
echo ""

# Start services
echo "[5/6] Starting services with Docker Compose..."
echo ""
echo "Starting: PostgreSQL, Redis, RabbitMQ, API, UI"
echo ""

docker-compose -f docker-compose.yml up -d --build --profile app

echo ""
echo "✓ Docker Compose services starting..."
echo ""

# Wait for services
echo "[6/6] Waiting for services to be healthy..."
echo ""

# Wait for API health
echo "Waiting for API to be ready..."
max_attempts=30
attempt=0
while [ $attempt -lt $max_attempts ]; do
    if docker exec stokr-api wget -qO- --timeout=5 http://localhost:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        echo "✓ API is healthy (http://localhost:8080)"
        break
    fi
    attempt=$((attempt + 1))
    echo "  Attempt $attempt/$max_attempts..."
    sleep 2
done

if [ $attempt -eq $max_attempts ]; then
    echo "❌ API failed to start after ${max_attempts} attempts"
    echo ""
    echo "Checking API logs:"
    docker logs stokr-api | tail -50
    exit 1
fi

echo ""
echo "Waiting for UI to be ready..."
sleep 5
echo "✓ UI should be ready (http://localhost:3000)"
echo ""

# Show status
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         ✅ APPLICATION STARTUP COMPLETE                        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Access Points:"
echo "  🌐 API:       http://localhost:8080"
echo "  🎨 UI:        http://localhost:3000"
echo "  🐘 Database:  localhost:5432 (user: stokr, password: stokr)"
echo "  🔴 Redis:     localhost:6379"
echo "  🐰 RabbitMQ:  localhost:5672 (UI: http://localhost:15672)"
echo ""
echo "Status:"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep stokr
echo ""
echo "Useful Commands:"
echo "  View logs:     docker-compose logs -f [service_name]"
echo "  Stop all:      docker-compose down"
echo "  Restart:       docker-compose restart"
echo "  Show status:   docker ps"
echo ""
echo "First Time Setup:"
echo "  1. Open http://localhost:3000 in your browser"
echo "  2. Create an admin account"
echo "  3. Go to Admin > Backtest Data"
echo "  4. Click 'Start Background Load' to load 5-year historical data"
echo ""
