import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print("=== Current full nginx config ===")
print(remote("cat /etc/nginx/sites-enabled/default"))
