#!/bin/bash
set -e
echo "=== Verify gzip via nginx (port 443 → 8081) ==="
echo "--- Via localhost:8081 (direct, no gzip) ---"
curl -s -H 'Accept-Encoding: gzip' -o /dev/null -w 'Size: %{size_download} bytes\n' 'http://localhost:8081/assets/react-vendor-BPN2y53-.js'
echo "--- Via stokr.in (nginx) ---"
curl -sk -H 'Accept-Encoding: gzip' -o /dev/null -w 'Size: %{size_download} bytes\n' 'https://stokr.in/assets/react-vendor-BPN2y53-.js'
echo ""
echo "--- Via localhost:8081 (main app) ---"
curl -s -H 'Accept-Encoding: gzip' -o /dev/null -w 'Size: %{size_download} bytes\n' 'http://localhost:8081/assets/index-CivKHboL.js'
echo "--- Via stokr.in (main app) ---"
curl -sk -H 'Accept-Encoding: gzip' -o /dev/null -w 'Size: %{size_download} bytes\n' 'https://stokr.in/assets/index-CivKHboL.js'
echo ""
echo "=== Check cache headers via nginx ==="
curl -skI 'https://stokr.in/assets/react-vendor-BPN2y53-.js' 2>&1 | grep -i 'cache-control\|content-encoding\|expires\|content-type'
echo ""
echo "=== Frontend cache headers ==="
curl -sI 'http://localhost:8081/assets/react-vendor-BPN2y53-.js' 2>&1 | grep -i 'cache-control\|expires\|content-type'
echo ""
echo "=== Full page load through nginx ==="
time curl -sk -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' 'https://stokr.in/'
echo ""
echo "=== Java heap after fix ==="
jstat -gcutil $(pgrep -f 'stokr-lite.jar') 2>/dev/null | tail -1
