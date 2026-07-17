#!/usr/bin/env python3
import subprocess

# Kill rogue Java process on 8081
r = subprocess.run(['ssh', '-o', 'StrictHostKeyChecking=no', 'root@173.249.55.84',
    'fuser -k 8081/tcp; sleep 3; ss -tlnp | grep 8081 || echo PORT_FREE'],
    capture_output=True, text=True, timeout=15)
print(r.stdout.strip())

# Now recreate containers
r2 = subprocess.run(['ssh', '-o', 'StrictHostKeyChecking=no', 'root@173.249.55.84',
    'cd /opt/stokr/stokr-platform/stokr-lite && docker compose down 2>&1 && sleep 2 && docker compose up -d 2>&1'],
    capture_output=True, text=True, timeout=120)
print(r2.stdout.strip())

# Verify network
import time
time.sleep(15)
r3 = subprocess.run(['ssh', '-o', 'StrictHostKeyChecking=no', 'root@173.249.55.84',
    'docker exec stokr-lite-backend ip addr 2>&1; echo ---; docker exec stokr-lite-backend ip route 2>&1; echo ---; curl -s -o /dev/null -w %{http_code} http://localhost:8081/api/option-arbitrage/health'],
    capture_output=True, text=True, timeout=30)
print(r3.stdout.strip())
