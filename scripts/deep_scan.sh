#!/bin/bash
set -e

echo "=== 1. All listening ports ==="
ss -tlnp

echo "=== 2. Rootkit persistence check ==="
echo "--- Cron ---"
crontab -l 2>/dev/null
echo "--- /etc/crontab ---"
cat /etc/crontab 2>/dev/null
echo "--- /etc/cron.d ---"
ls /etc/cron.d/ 2>/dev/null
echo "--- /etc/cron.hourly ---"
ls /etc/cron.hourly/ 2>/dev/null

echo "=== 3. Systemd services with suspicious names ==="
systemctl list-units --all --type=service 2>/dev/null | grep -iE "(kwork|snap\.(4r94|systemd)|cryptominer|miner)" || echo "none found"

echo "=== 4. All .service files ==="
find /etc/systemd/system -name "*.service" -type f 2>/dev/null

echo "=== 5. Check for running hidden/suspicious processes ==="
ps aux | grep -v "\[" | grep -E "(kwork|kw0rker|.snap)" | grep -v grep || echo "none running"

echo "=== 6. Check process exe links for deleted binaries ==="
for pid in $(ls /proc/ 2>/dev/null | grep -E "^[0-9]+$"); do
  exe=$(readlink /proc/$pid/exe 2>/dev/null)
  if echo "$exe" | grep -q "(deleted)"; then
    echo "PID $pid: $exe"
  fi
done 2>/dev/null

echo "=== 7. Check /var/tmp and /tmp for hidden dirs ==="
find /var/tmp /tmp -maxdepth 2 -name ".*" -type d 2>/dev/null
ls -la /var/tmp/ 2>/dev/null | grep -v systemd-private
ls -la /tmp/ 2>/dev/null | grep -v systemd-private

echo "=== 8. Check Docker containers restarts ==="
docker ps -a --format "table {{.Names}}\t{{.Status}}" 2>/dev/null

echo "=== 9. API containers health ==="
docker inspect stokr-api --format "{{.State.Health.Status}}" 2>/dev/null
