#!/bin/bash
set -e

echo "=== Rewrite nginx config properly ==="

cat > /etc/nginx/sites-enabled/default << 'NGINXEOF'
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name stokr.in www.stokr.in;

    ssl_certificate /etc/letsencrypt/live/stokr.in/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/stokr.in/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    # API routes → backend
    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
    }

    location /webhooks/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    # WebSocket support
    location /ws/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }

    # Opportunity Hunter Dashboard
    location /hunter/ {
        proxy_pass http://127.0.0.1:8090/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Static assets with fingerprinted names → long cache
    location ~* \.(js|css)$ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        expires 365d;
        add_header Cache-Control "public, immutable";
        access_log off;
    }

    # Frontend + everything else → backend
    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }
}

server {
    listen 80;
    listen [::]:80;
    server_name stokr.in www.stokr.in;
    return 301 https://$server_name$request_uri;
}
NGINXEOF

echo "Config rewritten"
echo ""

echo "=== Test nginx ==="
nginx -t 2>&1
if [ $? -eq 0 ]; then
    systemctl reload nginx
    echo "Nginx reloaded"
else
    echo "FAILED"
    exit 1
fi

echo ""
echo "=== Verify ==="
echo "--- Gzip test ---"
curl -sk -H 'Accept-Encoding: gzip' -o /dev/null -w 'Size: %{size_download} bytes\n' 'https://stokr.in/assets/react-vendor-BPN2y53-.js'
echo "--- Cache headers test ---"
curl -skI 'https://stokr.in/assets/react-vendor-BPN2y53-.js' 2>&1 | grep -i 'cache-control\|expires\|content-encoding\|content-type'
echo "--- Full page load ---"
time curl -sk -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s\n' 'https://stokr.in/'
