#!/usr/bin/env bash
set -euo pipefail
cd /opt/stokr/stokr-platform

upsert_env() {
  local key="$1"
  local val="$2"
  if grep -q "^${key}=" .env 2>/dev/null; then
    sed -i "s|^${key}=.*|${key}=${val}|" .env
  else
    echo "${key}=${val}" >> .env
  fi
}

upsert_env HIKARI_MAX_POOL_SIZE 30
upsert_env STOKR_CATALOG_SCAN_POLL_MS 15000
upsert_env STOKR_FEED_HEALTH_TICK_STALE_SEC 60
upsert_env STOKR_FEED_HEALTH_EQUITY_STALE_SEC 600
upsert_env STOKR_FEED_HEALTH_INDEX_STALE_SEC 600
upsert_env STOKR_FEED_HEALTH_OUTAGE_ERROR_SEC 900
upsert_env STOKR_FEED_HEALTH_SESSION_WARMUP_SEC 180

git pull origin Release_v1
./deploy.sh api

echo "==> Deploy complete"
docker compose --profile app ps api
docker logs stokr-api --since 2m 2>&1 | grep -iE 'Started|feed.health|live_rollout' | tail -15
