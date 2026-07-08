#!/bin/bash
set -e
echo "=== Check if static-cache.conf still exists ==="
ls -la /etc/nginx/snippets/static-cache.conf 2>/dev/null && cat /etc/nginx/snippets/static-cache.conf || echo "File does not exist"
echo ""
echo "=== Test direct asset through nginx ==="
curl -sk -v 'https://stokr.in/assets/react-vendor-BPN2y53-.js' 2>&1 | head -30
echo ""
echo "=== Test from Java directly ==="
curl -s 'http://localhost:8081/assets/react-vendor-BPN2y53-.js' | head -c 200
echo ""
echo "=== Check nginx error log ==="
tail -10 /var/log/nginx/error.log 2>/dev/null
echo ""
echo "=== Check nginx access log ==="
tail -10 /var/log/nginx/access.log 2>/dev/null | grep "react-vendor"
