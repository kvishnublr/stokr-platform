#!/usr/bin/env bash
# deploy.sh — fast partial deploy for stokr-platform
#
# Usage:
#   ./deploy.sh api        — rebuild + restart only the Spring Boot API
#   ./deploy.sh ui         — rebuild + restart only the React UI
#   ./deploy.sh api ui     — rebuild + restart both
#   ./deploy.sh            — auto-detect from git diff what changed
#
# The "jar" mode is even faster — builds JAR on host and hot-swaps into running container:
#   ./deploy.sh jar        — git pull → mvn build → docker cp → docker restart (no image rebuild)

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

CHANGED_API=false
CHANGED_UI=false

detect_changes() {
    local diff
    diff=$(git diff --name-only HEAD~1 HEAD 2>/dev/null || git diff --name-only ORIG_HEAD HEAD 2>/dev/null || echo "unknown")
    if echo "$diff" | grep -qE '^stokr-ui/'; then
        CHANGED_UI=true
    fi
    if echo "$diff" | grep -qvE '^(stokr-ui/|\.claude/|\.github/)'; then
        CHANGED_API=true
    fi
}

deploy_api_docker() {
    echo "==> [API] Pulling latest code..."
    git pull origin Release_v1

    echo "==> [API] Building Docker image (uses Maven layer cache)..."
    docker compose --profile app build api

    echo "==> [API] Restarting API container (without recreating dependencies)..."
    docker compose --profile app up -d --no-deps api

    echo "==> [API] Waiting for health check..."
    sleep 10
    docker compose ps api
}

deploy_ui_docker() {
    echo "==> [UI] Pulling latest code..."
    git pull origin Release_v1

    echo "==> [UI] Building Docker image..."
    docker compose --profile app build ui

    echo "==> [UI] Restarting UI container (without recreating dependencies)..."
    docker compose --profile app up -d --no-deps ui
    echo "==> [UI] Done."
}

deploy_jar() {
    echo "==> [JAR] Pulling latest code..."
    git pull origin Release_v1

    # Detect which modules changed since last deploy
    CHANGED_MODULES=$(git diff --name-only HEAD~1 HEAD 2>/dev/null \
        | grep -oE '^stokr-[a-z]+' | sort -u | tr '\n' ',' | sed 's/,$//')

    if [ -z "$CHANGED_MODULES" ]; then
        CHANGED_MODULES="stokr-bootstrap"
    fi

    BUILD_TARGETS="${CHANGED_MODULES},stokr-bootstrap"
    echo "==> [JAR] Building modules: $BUILD_TARGETS"

    mvn -pl "$BUILD_TARGETS" -am package -DskipTests -q

    echo "==> [JAR] Hot-swapping JAR into running container..."
    JAR=$(ls stokr-bootstrap/target/stokr-bootstrap-*.jar 2>/dev/null | head -1)
    if [ -z "$JAR" ]; then
        echo "ERROR: JAR not found in stokr-bootstrap/target/"
        exit 1
    fi
    docker cp "$JAR" stokr-api:/app/stokr-bootstrap.jar

    echo "==> [JAR] Restarting container..."
    docker restart stokr-api

    echo "==> [JAR] Waiting for startup..."
    sleep 15
    docker ps --filter name=stokr-api --format "Status: {{.Status}}"
    echo "==> [JAR] Done. Check logs: docker logs stokr-api -f"
}

# ── Main ──────────────────────────────────────────────────────────────────────

TARGETS=("$@")

if [ ${#TARGETS[@]} -eq 0 ]; then
    echo "==> Auto-detecting changes..."
    git pull origin Release_v1
    detect_changes
    if $CHANGED_API; then TARGETS+=("api"); fi
    if $CHANGED_UI;  then TARGETS+=("ui");  fi
    if [ ${#TARGETS[@]} -eq 0 ]; then
        echo "No changes detected. Nothing to deploy."
        exit 0
    fi
    echo "==> Detected changes in: ${TARGETS[*]}"
fi

for target in "${TARGETS[@]}"; do
    case "$target" in
        api)     deploy_api_docker ;;
        ui)      deploy_ui_docker  ;;
        jar)     deploy_jar        ;;
        *)
            echo "Unknown target: $target"
            echo "Usage: ./deploy.sh [api|ui|jar]"
            exit 1
            ;;
    esac
done

echo ""
echo "==> Deploy complete."
