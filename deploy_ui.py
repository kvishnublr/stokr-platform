import subprocess

def remote(cmd):
    r = subprocess.run([
        "ssh", "-o", "ConnectTimeout=30", "-o", "StrictHostKeyChecking=no",
        "root@173.249.55.84", cmd
    ], capture_output=True, text=True, timeout=30)
    print(r.stdout if r.stdout else r.stderr)

print("=== Deploying new frontend ===")
remote("rm -rf /opt/stokr/ui-old; mv /opt/stokr/ui /opt/stokr/ui-old; mv /opt/stokr/ui-new /opt/stokr/ui; rm -rf /opt/stokr/ui-old")
print("Done!")
