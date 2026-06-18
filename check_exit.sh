#!/bin/bash
echo "=== Restart policy ==="
docker inspect stokr-api --format '{{.HostConfig.RestartPolicy.Name}}'

echo ""
echo "=== Container state ==="
docker inspect stokr-api --format '{{json .State}}' | python3 -m json.tool 2>/dev/null || docker inspect stokr-api --format '{{json .State}}'

echo ""
echo "=== Logs around exit time (07:01:50 to 07:03:20) ==="
docker logs stokr-api 2>&1 | awk '/07:01:5[0-9]/,/07:03:20/' | head -30

echo ""
echo "=== Last 10 lines of full log ==="
docker logs stokr-api 2>&1 | tail -10
