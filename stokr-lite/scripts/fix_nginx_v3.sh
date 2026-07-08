#!/bin/bash
set -e

echo "=== Remove broken static-cache.conf ==="
rm -f /etc/nginx/snippets/static-cache.conf

echo "=== Write clean nginx config ==="
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

        # Override Spring Boot no-cache headers for all responses
        # Fingerprinted assets get 1 year cache; everything else gets short cache
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
            proxy_pass http://127.0.0.1:8081;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            expires 365d;
            add_header Cache-Control "public, immutable";
            access_log off;
        }
    }
}

server {
    listen 80;
    listen [::]:80;
    server_name stokr.in www.stokr.in;
    return 301 https://$server_name$request_uri;
}
NGINXEOF

echo "=== Test ==="
nginx -t 2>&1
if [ $? -eq 0 ]; then
    systemctl reload nginx
    echo "Nginx reloaded OK"
else
    echo "FAILED - reverting"
    exit 1
fi

echo ""
echo "=== Verify ==="
echo "--- Gzip (via nginx) ---"
curl -sk -H 'Accept-Encoding: gzip' -o /dev/null -w 'Compressed size: %{size_download} bytes\n' 'https://stokr.in/assets/react-vendor-BPN2y53-.js'
echo "Uncompressed: $(wc -c < /tmp/jar_extract/BOOT-INF/classes/static/assets/react-vendor-BPN2y53-.js) bytes"
echo ""
echo "--- Cache headers ---"
curl -skI 'https://stokr.in/assets/react-vendor-BPN2y53-.js' 2>&1 | grep -iE 'cache-control|expires|content-encoding|content-type'
echo ""
echo "--- Full page load ---"
time curl -sk -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s\n' 'https://stokr.in/'
echo ""
echo "--- JS bundle load ---"
time curl -sk -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download}\n' 'https://stokr.in/assets/react-vendor-BPN2y53-.js'
