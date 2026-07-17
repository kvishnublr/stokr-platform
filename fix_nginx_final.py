import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    print(r.stdout if r.stdout else r.stderr)

# Write the corrected nginx config
config = r"""server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name stokr.in www.stokr.in;

    ssl_certificate /etc/letsencrypt/live/stokr.in/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/stokr.in/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;

    root /opt/stokr/ui;
    index index.html;

    # Never cache index.html — forces browser to pick up new JS hashes
    location = /index.html {
        add_header Cache-Control "no-cache, no-store, must-revalidate";
        add_header Pragma "no-cache";
        add_header Expires "0";
    }

    # Cache fingerprinted assets (JS/CSS with hashes)
    location ~* \.(js|css|woff2?|ttf|eot|svg|png|jpg|gif|ico)$ {
        expires 365d;
        add_header Cache-Control "public, immutable";
        access_log off;
    }

    # API routes -> backend
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

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }
}

server {
    listen 80;
    listen [::]:80;
    server_name stokr.in www.stokr.in;
    return 301 https://$server_name$request_uri;
}"""

# Write config to server
import tempfile, os
local_cfg = os.path.join(tempfile.gettempdir(), "nginx_site.conf")
with open(local_cfg, "w") as f:
    f.write(config)

os.system(f'scp -o StrictHostKeyChecking=no "{local_cfg}" root@173.249.55.84:/etc/nginx/sites-enabled/default')

print("=== Testing nginx config ===")
remote("nginx -t 2>&1")

print("\n=== Reloading nginx ===")
remote("systemctl reload nginx 2>&1")

print("\n=== Test JS via public HTTPS ===")
print(remote("curl -sIk 'https://stokr.in/assets/index-DlcA_l1r.js' 2>&1 | head -10"))

print("\n=== Test / via public HTTPS ===")
print(remote("curl -sIk 'https://stokr.in/' 2>&1 | head -10"))

print("\n=== Test /login via public HTTPS ===")
print(remote("curl -sIk 'https://stokr.in/login' 2>&1 | head -10"))
