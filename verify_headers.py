import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print("=== Check nginx config after sed ===")
print(remote("grep -A 10 'Never cache' /etc/nginx/sites-enabled/default"))

print("\n=== Test headers for / ===")
print(remote("curl -sI https://stokr.in/ 2>&1 | head -20"))

print("\n=== Test headers for JS file ===")
print(remote("curl -sI 'https://stokr.in/assets/index-DlcA_l1r.js' 2>&1 | head -20"))
