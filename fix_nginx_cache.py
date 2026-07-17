import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    print(r.stdout if r.stdout else r.stderr)

# Patch nginx config to add no-cache for index.html
remote("""sed -i '/# Frontend static files/a\\
\\
    # Never cache index.html — forces browser to pick up new JS hashes\\
    location = /index.html {\\
        add_header Cache-Control "no-cache, no-store, must-revalidate";\\
        add_header Pragma "no-cache";\\
        add_header Expires "0";\\
    }' /etc/nginx/sites-enabled/default""")

print("=== Testing nginx config ===")
remote("nginx -t 2>&1")

print("\n=== Reloading nginx ===")
remote("systemctl reload nginx 2>&1")

print("\n=== Verify ===")
remote("curl -s -o /dev/null -w '%{http_code}' -H 'Host: stokr.in' http://localhost/index.html 2>&1")
