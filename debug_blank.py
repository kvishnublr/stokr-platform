import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print("=== index.html exists? ===")
print(remote("cat /opt/stokr/ui/index.html 2>&1"))

print("\n=== JS files in assets? ===")
print(remote("ls -la /opt/stokr/ui/assets/index-*.js 2>&1"))

print("\n=== nginx serving correctly? ===")
print(remote("curl -s -o /dev/null -w '%{http_code}' http://localhost/ 2>&1"))

print("\n=== nginx config ===")
print(remote("cat /etc/nginx/sites-enabled/default 2>&1 | head -40"))

print("\n=== Try fetching the page ===")
print(remote("curl -s http://localhost/ | head -5"))

print("\n=== Check for any JS errors in browser by checking if JS files load ===")
print(remote("curl -s -o /dev/null -w '%{http_code}' http://localhost/assets/index-DlcA_l1r.js 2>&1"))
