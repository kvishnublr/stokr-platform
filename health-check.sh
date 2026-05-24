#!/usr/bin/env bash
# health-check.sh — Verify all stokr-platform services are healthy
#
# Usage:
#   ./health-check.sh              — check all services
#   ./health-check.sh restart      — restart unhealthy services
#   ./health-check.sh full-restart — do a full stack restart

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Service names and their health check methods
declare -A SERVICES=(
    [stokr-postgres]="pg_isready check"
    [stokr-redis]="redis-cli ping"
    [stokr-rabbitmq]="rabbitmq-diagnostics ping"
    [stokr-api]="curl -f http://localhost:8080/actuator/health"
    [stokr-ui]="curl -f http://localhost:3000"
)

check_service() {
    local service=$1
    local cmd=$2

    # Check if container exists and is running
    if ! docker ps --filter name="$service" --format "{{.Names}}" | grep -q "$service"; then
        echo -e "${RED}✗${NC} $service: Container not running"
        return 1
    fi

    # Check health based on service type
    case $service in
        stokr-postgres)
            if docker exec "$service" pg_isready -U stokr -d stokr_platform > /dev/null 2>&1; then
                echo -e "${GREEN}✓${NC} $service: Healthy"
                return 0
            else
                echo -e "${RED}✗${NC} $service: Database not responding"
                return 1
            fi
            ;;
        stokr-redis)
            if docker exec "$service" redis-cli ping > /dev/null 2>&1; then
                echo -e "${GREEN}✓${NC} $service: Healthy"
                return 0
            else
                echo -e "${RED}✗${NC} $service: Redis not responding"
                return 1
            fi
            ;;
        stokr-rabbitmq)
            if docker exec "$service" rabbitmq-diagnostics -q ping > /dev/null 2>&1; then
                echo -e "${GREEN}✓${NC} $service: Healthy"
                return 0
            else
                echo -e "${RED}✗${NC} $service: RabbitMQ not responding"
                return 1
            fi
            ;;
        stokr-api)
            if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
                echo -e "${GREEN}✓${NC} $service: Healthy"
                return 0
            else
                echo -e "${RED}✗${NC} $service: API not responding (may be starting...)"
                return 1
            fi
            ;;
        stokr-ui)
            if curl -sf http://localhost:3000 > /dev/null 2>&1; then
                echo -e "${GREEN}✓${NC} $service: Healthy"
                return 0
            else
                echo -e "${RED}✗${NC} $service: UI not responding"
                return 1
            fi
            ;;
    esac
}

check_all() {
    echo "==> Checking stokr-platform services..."
    echo ""

    local all_healthy=true
    for service in stokr-postgres stokr-redis stokr-rabbitmq stokr-api stokr-ui; do
        if ! check_service "$service"; then
            all_healthy=false
        fi
    done

    echo ""
    if [ "$all_healthy" = true ]; then
        echo -e "${GREEN}All services healthy!${NC}"
        return 0
    else
        echo -e "${YELLOW}Some services are unhealthy. Run: ./health-check.sh restart${NC}"
        return 1
    fi
}

restart_services() {
    echo "==> Restarting unhealthy services..."

    # Start all app services
    docker compose --profile app up -d

    echo ""
    echo "==> Waiting for services to stabilize (30 seconds)..."
    sleep 30

    echo ""
    check_all
}

full_restart() {
    echo "==> Full stack restart..."
    echo "WARNING: This will stop and restart ALL services."
    echo "Existing data will be preserved in volumes."
    read -p "Continue? (yes/no) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Cancelled."
        exit 0
    fi

    docker compose --profile app down

    echo "==> Starting full stack..."
    docker compose --profile app up -d

    echo "==> Waiting for all services to be healthy..."
    sleep 60

    echo ""
    check_all
}

# Main
ACTION="${1:-check}"

case "$ACTION" in
    check)
        check_all
        ;;
    restart)
        restart_services
        ;;
    full-restart)
        full_restart
        ;;
    *)
        echo "Usage: ./health-check.sh [check|restart|full-restart]"
        exit 1
        ;;
esac
