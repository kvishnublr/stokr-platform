#!/usr/bin/env python3
import subprocess

checks = [
    "docker inspect stokr-lite-backend | python3 -c 'import sys,json; d=json.load(sys.stdin)[0]; print(\"State:\",d[\"State\"][\"Status\"]); print(\"Networks:\",list(d[\"NetworkSettings\"].get(\"Networks\",{}).keys()))'",
    "docker exec stokr-lite-backend cat /etc/hosts",
    "docker exec stokr-lite-backend ip route",
    "ss -tlnp | grep 5432",
    "docker network connect stokr-lite_stokr-net stokr-lite-backend 2>&1 || echo ALREADY_CONNECTED",
]

for cmd in checks:
    print(f"\n>>> {cmd}")
    r = subprocess.run(['ssh', 'root@173.249.55.84', cmd], capture_output=True, text=True, timeout=15)
    print(r.stdout.strip())
    if r.stderr.strip():
        print(f"  ERR: {r.stderr.strip()[:200]}")
