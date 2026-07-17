#!/usr/bin/env python3
import subprocess, json

# Check container network state
r = subprocess.run(['ssh', 'root@173.249.55.84', 'docker inspect stokr-lite-backend'], capture_output=True, text=True, timeout=15)
data = json.loads(r.stdout)[0]
ns = data['NetworkSettings']
state = data['State']['Status']
net_mode = data['HostConfig'].get('NetworkMode', '')
networks = list(ns.get('Networks', {}).keys())
ports = ns.get('Ports', {})
print(f"State: {state}")
print(f"NetworkMode: {net_mode}")
print(f"Networks: {networks}")
print(f"Ports: {ports}")

# Check host.docker.internal resolution inside container
r2 = subprocess.run(['ssh', 'root@173.249.55.84', 'docker exec stokr-lite-backend sh -c "cat /etc/hosts | grep host"'], capture_output=True, text=True, timeout=15)
print(f"\n/etc/hosts inside container:\n{r2.stdout}")

# Check if we can ping the DB from inside the container
r3 = subprocess.run(['ssh', 'root@173.249.55.84', 'docker exec stokr-lite-backend sh -c "wget -q -O /dev/null http://host.docker.internal:5432 2>&1 || echo CONNECTION_FAILED"'], capture_output=True, text=True, timeout=15)
print(f"DB connectivity: {r3.stdout.strip()}")

# Try to connect using the actual docker bridge IP
r4 = subprocess.run(['ssh', 'root@173.249.55.84', 'docker exec stokr-lite-backend sh -c "ip route | head -5"'], capture_output=True, text=True, timeout=15)
print(f"Routes:\n{r4.stdout}")

# Check if PostgreSQL is listening on all interfaces
r5 = subprocess.run(['ssh', 'root@173.249.55.84', 'ss -tlnp | grep 5432'], capture_output=True, text=True, timeout=15)
print(f"PostgreSQL listening:\n{r5.stdout}")
