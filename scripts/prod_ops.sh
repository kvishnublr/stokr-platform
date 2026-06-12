#!/usr/bin/env bash
# Unified production operations CLI for stokr-platform.
# Usage: prod_ops.sh {deploy|deploy-api|deploy-ui|health|pre-market|pre-open|in-session|refresh-tokens|recovery|verify|all}
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

STOKR_API_BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
STOKR_DEPLOY_BRANCH="${STOKR_DEPLOY_BRANCH:-Release_v2}"
STOKR_OPS_ADMIN_USER="${STOKR_OPS_ADMIN_USER:-admin@stokr.local}"
STOKR_OPS_ADMIN_PASS="${STOKR_OPS_ADMIN_PASS:-admin123}"
STOKR_PUBLIC_BASE="${STOKR_PUBLIC_BASE:-}"

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$REPO_ROOT/.env"
  set +a
  STOKR_API_BASE="${STOKR_API_BASE:-http://127.0.0.1:8080}"
  STOKR_DEPLOY_BRANCH="${STOKR_DEPLOY_BRANCH:-Release_v2}"
  STOKR_OPS_ADMIN_USER="${STOKR_OPS_ADMIN_USER:-admin@stokr.local}"
  STOKR_OPS_ADMIN_PASS="${STOKR_OPS_ADMIN_PASS:-admin123}"
fi

LOG_DIR="/var/log/stokr"
if ! mkdir -p "$LOG_DIR" 2>/dev/null || [[ ! -w "$LOG_DIR" ]]; then
  LOG_DIR="$SCRIPT_DIR/logs"
  mkdir -p "$LOG_DIR"
fi
LOG_FILE="$LOG_DIR/ops.log"

log() {
  local msg="[$(date -Iseconds)] $*"
  echo "$msg"
  echo "$msg" >> "$LOG_FILE"
}

usage() {
  echo "Usage: $(basename "$0") {deploy|deploy-api|deploy-ui|health|pre-market|pre-open|in-session|refresh-tokens|recovery|verify|all}"
  exit 1
}

admin_token() {
  local payload
  payload="$(printf '{"principal":"%s","password":"%s"}' "$STOKR_OPS_ADMIN_USER" "$STOKR_OPS_ADMIN_PASS")"
  curl -sf -X POST "$STOKR_API_BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "$payload" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("data",{}).get("accessToken",""))'
}

admin_post() {
  local path="$1"
  local token
  token="$(admin_token)"
  if [[ -z "$token" ]]; then
    log "ERROR admin login failed for $STOKR_OPS_ADMIN_USER"
    return 1
  fi
  curl -sf -X POST "$STOKR_API_BASE$path" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json"
}

admin_get() {
  local path="$1"
  local token
  token="$(admin_token)"
  if [[ -z "$token" ]]; then
    log "ERROR admin login failed for $STOKR_OPS_ADMIN_USER"
    return 1
  fi
  curl -sf "$STOKR_API_BASE$path" -H "Authorization: Bearer $token"
}

automation_post_or_fallback() {
  local automation_path="$1"
  local fallback_desc="$2"
  if admin_post "$automation_path" 2>/dev/null; then
    return 0
  fi
  log "WARN automation endpoint unavailable ($automation_path), fallback: $fallback_desc"
  curl -sf "$STOKR_API_BASE/actuator/health" || true
  admin_get "/api/admin/broker-infrastructure" 2>/dev/null || true
  return 0
}

cmd_deploy_api() {
  log "deploy-api branch=$STOKR_DEPLOY_BRANCH"
  cd "$REPO_ROOT"
  export STOKR_DEPLOY_BRANCH
  ./deploy.sh api
}

cmd_deploy_ui() {
  log "deploy-ui branch=$STOKR_DEPLOY_BRANCH"
  cd "$REPO_ROOT"
  export STOKR_DEPLOY_BRANCH
  ./deploy.sh ui
}

cmd_deploy() {
  log "deploy api+ui branch=$STOKR_DEPLOY_BRANCH"
  cd "$REPO_ROOT"
  export STOKR_DEPLOY_BRANCH
  ./deploy.sh api ui
}

cmd_health() {
  log "health-report"
  local out
  out="$(automation_post_or_fallback "/api/admin/operations/automation/health-report" "actuator+broker-infrastructure")"
  echo "$out"
  if echo "$out" | python3 -c 'import sys,json; d=json.load(sys.stdin); data=d.get("data",d); sys.exit(0 if data.get("healthy") else 1)' 2>/dev/null; then
    log "health OK"
  else
    log "health check reported blockers or endpoint unavailable"
  fi
}

cmd_pre_market() {
  log "pre-market"
  automation_post_or_fallback "/api/admin/operations/automation/pre-market" "actuator+broker-infrastructure"
}

cmd_pre_open() {
  log "pre-open"
  automation_post_or_fallback "/api/admin/operations/automation/pre-open" "actuator+broker-infrastructure"
}

cmd_in_session() {
  log "in-session"
  automation_post_or_fallback "/api/admin/operations/automation/in-session" "actuator+broker-infrastructure"
}

cmd_refresh_tokens() {
  log "refresh-tokens"
  automation_post_or_fallback "/api/admin/operations/automation/refresh-tokens" "broker-infrastructure"
}

cmd_recovery() {
  log "recovery-cycle"
  automation_post_or_fallback "/api/admin/operations/automation/recovery-cycle" "actuator"
}

cmd_verify() {
  log "verify"
  docker ps --format 'table {{.Names}}\t{{.Status}}' || log "WARN docker ps failed"
  curl -sf "$STOKR_API_BASE/actuator/health" | python3 -m json.tool || log "WARN actuator health failed"
  if [[ -n "$STOKR_PUBLIC_BASE" ]]; then
    curl -sfI "$STOKR_PUBLIC_BASE" | head -n 1 || log "WARN public base check failed: $STOKR_PUBLIC_BASE"
  else
    curl -sfI "https://stokr.in" | head -n 1 || log "WARN stokr.in check failed"
    curl -sfI "https://new.stokr.in" | head -n 1 || log "WARN new.stokr.in check failed"
  fi
}

cmd_all() {
  cmd_deploy_api
  cmd_health
  cmd_pre_market
}

main() {
  local action="${1:-}"
  case "$action" in
    deploy) cmd_deploy ;;
    deploy-api) cmd_deploy_api ;;
    deploy-ui) cmd_deploy_ui ;;
    health) cmd_health ;;
    pre-market) cmd_pre_market ;;
    pre-open) cmd_pre_open ;;
    in-session) cmd_in_session ;;
    refresh-tokens) cmd_refresh_tokens ;;
    recovery) cmd_recovery ;;
    verify) cmd_verify ;;
    all) cmd_all ;;
    *) usage ;;
  esac
}

main "$@"
