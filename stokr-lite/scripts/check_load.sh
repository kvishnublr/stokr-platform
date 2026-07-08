#!/bin/bash
set -e
echo "=== Full page load timing ==="
echo "--- Static frontend (HTML) ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download} bytes - Speed: %{speed_download} bytes/s\n' https://stokr.in/ 2>/dev/null || echo "HTTPS not accessible locally"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download} bytes\n' http://localhost:8081/ 
echo ""
echo "--- React vendor bundle ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download} bytes\n' 'http://localhost:8081/assets/react-vendor-BPN2y53-.js'
echo ""
echo "--- Main app bundle ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download} bytes\n' 'http://localhost:8081/assets/index-CivKHboL.js'
echo ""
echo "--- CSS ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download} bytes\n' 'http://localhost:8081/assets/index-DNELjdCp.css'
echo ""
echo "--- AdvancedBacktest chunk ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download} bytes\n' 'http://localhost:8081/assets/AdvancedBacktest-BHQcbX62.js'
echo ""
echo "--- Dashboard chunk ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download} bytes\n' 'http://localhost:8081/assets/Dashboard-nf6fopBr.js'
echo ""
echo "--- Signals chunk ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - Total: %{time_total}s - Size: %{size_download} bytes\n' 'http://localhost:8081/assets/Signals-Cg21ei1r.js'
echo ""
echo "=== Check if gzip is enabled ==="
curl -s -o /dev/null -w 'Content-Encoding: %{header_json}\n' -H 'Accept-Encoding: gzip' 'http://localhost:8081/assets/react-vendor-BPN2y53-.js' 2>&1 | head -5
echo ""
echo "=== Check nginx gzip ==="
grep -i gzip /etc/nginx/nginx.conf /etc/nginx/sites-enabled/default 2>/dev/null || echo "no gzip config found"
echo ""
echo "=== Check browser cache headers ==="
curl -sI 'http://localhost:8081/assets/react-vendor-BPN2y53-.js' 2>&1 | grep -i 'cache-control\|etag\|last-modified\|content-encoding\|content-type'
echo ""
echo "=== Java memory usage ==="
jstat -gcutil $(pgrep -f 'stokr-lite.jar') 2>/dev/null | tail -1
echo ""
echo "=== WebSocket connections ==="
ss -tnp | grep 8081 | grep -i ws 2>/dev/null || echo "No websocket connections on port 8081"
echo ""
echo "=== Total TCP connections to backend ==="
ss -tnp | grep 8081 | wc -l
