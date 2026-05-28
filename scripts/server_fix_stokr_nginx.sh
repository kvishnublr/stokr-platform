#!/usr/bin/env bash
# Run ON Contabo after deploy if admin console shows HTTP 502 / ops stream disconnected.
# Fixes duplicate nginx site configs and adds SSE-safe proxy for /api/admin/operations/stream.
set -euo pipefail

echo "==> Remove duplicate nginx site symlinks"
cd /etc/nginx/sites-enabled
for f in stokr.bak stokr.bak.* stokr.bak2 stokr.bak_fix; do
  [ -e "$f" ] && rm -f "$f" && echo "removed $f"
done

echo "==> Install stokr nginx config"
cat > /etc/nginx/sites-available/stokr <<'NGINX'
server {
    server_name stokr.in www.stokr.in;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /api/admin/operations/stream {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        chunked_transfer_encoding off;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 15s;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }

    listen 443 ssl;
    ssl_certificate /etc/letsencrypt/live/stokr.in/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/stokr.in/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
}

ln -sf /etc/nginx/sites-available/stokr /etc/nginx/sites-enabled/stokr
systemctl reload nginx
echo "==> nginx reloaded"

ROOT="${ROOT:-/opt/stokr/stokr-platform}"
if [ -d "$ROOT" ]; then
  echo "==> Recreate API container so .env URLs match (stokr.in)"
  cd "$ROOT"
  docker compose --profile app up -d api --force-recreate
  echo "==> Wait for API..."
  for i in $(seq 1 24); do
    curl -sf http://127.0.0.1:8080/actuator/health >/dev/null && break
    sleep 5
  done
  ./health-check.sh check || true
fi

echo "==> Done"
