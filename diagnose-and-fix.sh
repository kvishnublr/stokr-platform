#!/bin/bash
set -euo pipefail

echo "=============================================="
echo "  STOKR PLATFORM - FULL DIAGNOSTIC & FIX"
echo "  $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "=============================================="

echo -e "\n=== 1. SYSTEM RESOURCES ==="
echo "--- Disk ---"
df -h / | tail -1
echo "--- Memory ---"
free -h | head -2
echo "--- CPU Load ---"
uptime

echo -e "\n=== 2. ALL DOCKER CONTAINERS ==="
docker ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Image}}\t{{.Ports}}"

echo -e "\n=== 3. ALL DOCKER IMAGES ==="
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedSince}}"

echo -e "\n=== 4. DOCKER NETWORKS ==="
docker network ls

echo -e "\n=== 5. PORTS IN USE ==="
ss -tlnp | grep -E ':(80|443|3000|5432|6379|5672|8080|8081|8082|8090|15672)\b'

echo -e "\n=== 6. GIT STATE ==="
cd /root/stokr-platform 2>/dev/null || cd /root
pwd
git remote -v 2>/dev/null || echo "Not a git repo"
git branch 2>/dev/null || true
git log --oneline -5 2>/dev/null || true

echo -e "\n=== 7. FIND ALL DOCKER COMPOSE FILES ==="
find / -name "docker-compose*.yml" -not -path "*/proc/*" -not -path "*/sys/*" 2>/dev/null || echo "None found"

echo -e "\n=== 8. FIND ALL DOCKERFILES ==="
find /root -name "Dockerfile*" 2>/dev/null || echo "None found"

echo -e "\n=== 9. FIND ALL .ENV FILES ==="
find /root -name ".env" 2>/dev/null || echo "None found"

echo -e "\n=== 10. DIRECTORY STRUCTURE ==="
echo "--- /root contents ---"
ls /root/
echo "--- /root/stokr-platform contents (top level) ---"
ls /root/stokr-platform/ 2>/dev/null | head -40 || echo "Dir not found"
echo "--- stokr-lite ---"
ls /root/stokr-platform/stokr-lite/ 2>/dev/null || echo "Not found"
echo "--- stokr-lite/frontend ---"
ls /root/stokr-platform/stokr-lite/frontend/ 2>/dev/null || echo "Not found"
echo "--- stokr-lite/backend ---"
ls /root/stokr-platform/stokr-lite/backend/ 2>/dev/null || echo "Not found"

echo -e "\n=== 11. CONTAINER INSPECT ==="
for c in stokr-lite-frontend stokr-lite-backend stokr-api stokr-postgres stokr-redis stokr-rabbitmq stokr-ui; do
  echo "--- $c ---"
  docker inspect "$c" --format 'Image={{.Config.Image}} Created={{.Created}} Status={{.State.Status}} ExitCode={{.State.ExitCode}} Error={{.State.Error}}' 2>&1 || echo "Container not found"
done

echo -e "\n=== 12. CONTAINER LOGS ==="
for c in stokr-lite-frontend stokr-lite-backend; do
  echo "--- $c (last 20 lines) ---"
  docker logs "$c" --tail 20 2>&1 || echo "No logs"
done

echo -e "\n=== 13. NGINX CONFIG (host) ==="
cat /etc/nginx/sites-enabled/default 2>/dev/null | head -60 || cat /etc/nginx/nginx.conf 2>/dev/null | head -60 || echo "No nginx config found"
ls /etc/nginx/sites-enabled/ 2>/dev/null || true

echo -e "\n=== 14. SYSTEMD SERVICES ==="
systemctl list-units --type=service --state=running | grep -iE "docker|nginx|caddy|postgres|redis|rabbit" || echo "No matching services"

echo -e "\n=== 15. CRONTABS ==="
crontab -l 2>/dev/null || echo "No crontab"

echo -e "\n=== 16. HEALTH CHECKS ==="
echo "--- localhost:8080 (main API) ---"
curl -s --max-time 5 http://localhost:8080/actuator/health 2>&1 || echo "NOT RESPONDING"
echo ""
echo "--- localhost:8081 (lite backend) ---"
curl -s --max-time 5 http://localhost:8081/api/signals 2>&1 | head -c 200 || echo "NOT RESPONDING"
echo ""
echo "--- localhost:3000 (main UI) ---"
curl -s --max-time 5 -o /dev/null -w "HTTP %{http_code}" http://localhost:3000 2>&1 || echo "NOT RESPONDING"
echo ""
echo "--- localhost:8082 (lite frontend) ---"
curl -s --max-time 5 -o /dev/null -w "HTTP %{http_code}" http://localhost:8082 2>&1 || echo "NOT RESPONDING"
echo ""
echo "--- localhost:5432 (postgres) ---"
docker exec stokr-postgres psql -U postgres -c "SELECT 1" 2>&1 || echo "NOT RESPONDING"

echo -e "\n=== 17. DOCKER COMPOSE IN STOKR-LITE ==="
cat /root/stokr-platform/stokr-lite/docker-compose.yml 2>/dev/null || echo "Not found"

echo -e "\n=== 18. MAIN DOCKER COMPOSE ==="
cat /root/stokr-platform/docker-compose.yml 2>/dev/null || echo "Not found"

echo -e "\n=============================================="
echo "  DIAGNOSTIC COMPLETE"
echo "=============================================="
