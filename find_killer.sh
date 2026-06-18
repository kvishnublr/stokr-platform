#!/bin/bash
echo "=== Docker events for stokr-api (last 10 min) ==="
docker events --since 10m --filter container=stokr-api 2>&1 | head -20

echo ""
echo "=== All docker containers ==="
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Command}}'

echo ""
echo "=== Checking for docker-compose ==="
ls -la /opt/stokr*/docker-compose*.yml /opt/stokr*/compose*.yml /opt/stokr-platform/docker-compose*.yml 2>/dev/null

echo ""
echo "=== Checking systemd ==="
systemctl list-units --type=service --state=running 2>/dev/null | grep -i stokr\|docker\|contain
