import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    return r.stdout + r.stderr

print("=== Test JS via localhost (no SSL) ===")
print(remote("curl -sI 'http://localhost/assets/index-DlcA_l1r.js' 2>&1 | head -15"))

print("\n=== Test / via localhost ===")
print(remote("curl -sI 'http://localhost/' 2>&1 | head -15"))

print("\n=== File exists on disk? ===")
print(remote("ls -la /opt/stokr/ui/assets/index-DlcA_l1r.js 2>&1"))

print("\n=== Test via public URL ===")
print(remote("curl -sIk 'https://stokr.in/assets/index-DlcA_l1r.js' 2>&1 | head -15"))
