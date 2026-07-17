#!/usr/bin/env python3
import subprocess, json

# Run a single combined check
script = """
docker inspect stokr-lite-backend --format '{{.State.Status}}' 2>/dev/null
docker inspect stokr-lite-backend --format '{{.HostConfig.NetworkMode}}' 2>/dev/null
ss -tlnp | grep 5432
docker exec stokr-lite-backend sh -c 'cat /etc/hosts' 2>/dev/null
docker exec stokr-lite-backend sh -c 'ip route' 2>/dev/null
"""
r = subprocess.run(['ssh', '-o', 'StrictHostKeyChecking=no', 'root@173.249.55.84', script],
                   capture_output=True, text=True, timeout=15)
print(r.stdout)
if r.stderr:
    print("STDERR:", r.stderr[:300])
