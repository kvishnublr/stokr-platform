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
DEPLOY_STATE_FILE="$PROJECT_DIR/.deploy-last-sha"

detect_changes() {
    local diff=""
    local prev_sha=""
    local current_sha
    current_sha="$(git rev-parse HEAD 2>/dev/null || echo "")"
    if [ -f "$DEPLOY_STATE_FILE" ]; then
        prev_sha="$(tr -d '[:space:]' < "$DEPLOY_STATE_FILE")"
    fi
    if [ -n "$prev_sha" ] && [ -n "$current_sha" ] && [ "$prev_sha" != "$current_sha" ]; then
        diff="$(git diff --name-only "$prev_sha" "$current_sha" 2>/dev/null || true)"
    fi
    if [ -z "$diff" ]; then
        diff="$(git diff --name-only HEAD~1 HEAD 2>/dev/null || git diff --name-only ORIG_HEAD HEAD 2>/dev/null || echo "unknown")"
    fi
    if echo "$diff" | grep -qE '^stokr-ui/'; then
        CHANGED_UI=true
    fi
    if echo "$diff" | grep -qvE '^(stokr-ui/|\.claude/|\.github/|deploy/|scripts/contabo_|scripts/server_deploy)'; then
        CHANGED_API=true
    fi
    if [ "$diff" = "unknown" ] || [ -z "$diff" ]; then
        CHANGED_API=true
        CHANGED_UI=true
    fi
}

record_deploy_sha() {
    git rev-parse HEAD > "$DEPLOY_STATE_FILE" 2>/dev/null || true
}

export_deploy_metadata() {
    export STOKR_GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
    export STOKR_DEPLOY_BRANCH="${STOKR_DEPLOY_BRANCH:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo Release_v1)}"
    echo "==> Deploy metadata: branch=$STOKR_DEPLOY_BRANCH commit=$STOKR_GIT_COMMIT"
}

git_pull_deploy_branch() {
    git pull origin "$STOKR_DEPLOY_BRANCH"
}

deploy_api_docker() {
    export_deploy_metadata
    echo "==> [API] Pulling latest code..."
    git_pull_deploy_branch

    echo "==> [API] Ensuring dependencies are running (postgres, redis, rabbitmq, autoheal)..."
    docker compose --profile app up -d postgres redis rabbitmq autoheal
    echo "==> [API] Waiting for dependencies to be healthy (60 seconds)..."
    sleep 60

    echo "==> [API] Building Docker image (uses Maven layer cache)..."
    docker compose --profile app build api

    echo "==> [API] Restarting API container (without recreating postgres/redis)..."
    docker compose --profile app up -d --no-deps --force-recreate api

    echo "==> [API] Waiting for health check..."
    sleep 15
    docker compose ps api
}

deploy_ui_docker() {
    export_deploy_metadata
    echo "==> [UI] Pulling latest code..."
    git_pull_deploy_branch

    echo "==> [UI] Ensuring API is running and healthy..."
    if ! curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "==> [UI] API is not healthy. Restarting API first..."
        docker compose --profile app up -d postgres redis rabbitmq autoheal
        sleep 60
        docker compose --profile app up -d --no-deps --force-recreate api
        echo "==> [UI] Waiting for API to be ready..."
        sleep 15
    fi

    echo "==> [UI] Building Docker image..."
    docker compose --profile app build ui

    echo "==> [UI] Restarting UI container (without recreating dependencies)..."
    docker compose --profile app up -d --no-deps --force-recreate ui
    echo "==> [UI] Done."
}

deploy_jar() {
    export_deploy_metadata
    echo "==> [JAR] Pulling latest code..."
    git_pull_deploy_branch

    echo "==> [JAR] Ensuring dependencies are running (postgres, redis, rabbitmq, autoheal)..."
    docker compose --profile app up -d postgres redis rabbitmq autoheal
    echo "==> [JAR] Waiting for dependencies to be healthy (30 seconds)..."
    sleep 30

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

if [ "$(id -u)" = "0" ] && [ -f "$PROJECT_DIR/scripts/sync_github_deploy_authorized_key.sh" ]; then
    bash "$PROJECT_DIR/scripts/sync_github_deploy_authorized_key.sh"
fi

TARGETS=("$@")

if [ ${#TARGETS[@]} -eq 0 ]; then
    export_deploy_metadata
    echo "==> Auto-detecting changes..."
    git_pull_deploy_branch
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

record_deploy_sha

echo ""
echo "==> Deploy complete."
