import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print("=== File permissions ===")
print(remote("ls -la /opt/stokr/ui/"))
print(remote("ls -la /opt/stokr/ui/assets/ | head -5"))

print("\n=== Directory permissions ===")
print(remote("stat /opt/stokr/ui /opt/stokr/ui/assets"))
