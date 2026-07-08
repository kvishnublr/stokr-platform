#!/bin/bash
set -e

echo "=== Frontend size breakdown ==="
echo "Main bundle (react-vendor):"
wc -c /tmp/jar_extract/BOOT-INF/classes/static/assets/react-vendor-BPN2y53-.js
echo "Index (main app):"
wc -c /tmp/jar_extract/BOOT-INF/classes/static/assets/index-CivKHboL.js
echo "CSS:"
wc -c /tmp/jar_extract/BOOT-INF/classes/static/assets/index-DNELjdCp.css
echo ""
echo "=== All JS files total ==="
find /tmp/jar_extract/BOOT-INF/classes/static -name "*.js" -exec cat {} + | wc -c
echo " bytes"
echo ""
echo "=== API response times ==="
echo "--- /api/strategies ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/strategies
echo ""
echo "--- /api/deployments ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/deployments
echo ""
echo "--- /api/orders ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/orders
echo ""
echo "--- /api/positions ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/positions
echo ""
echo "--- /api/signals ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/signals
echo ""
echo "--- /api/profile ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/profile
echo ""
echo "--- /api/dashboard/summary ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/dashboard/summary
echo ""
echo "--- /api/risk/portfolio ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/risk/portfolio
echo ""
echo "--- /api/daily-pnl ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/daily-pnl
echo ""
echo "--- /api/admin/audit-logs ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/admin/audit-logs
echo ""
echo "--- /api/backtest/available ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/backtest/available
echo ""
echo "--- /api/pairs ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/pairs
echo ""
echo "--- /api/tick/anomalies ---"
time curl -s -o /dev/null -w 'HTTP %{http_code} - TTFB: %{time_starttransfer}s - Total: %{time_total}s - Size: %{size_download}\n' http://localhost:8081/api/tick/anomalies
echo ""
echo "=== Java heap ==="
jstat -gcutil $(pgrep -f stokr-lite) 2>/dev/null || echo "jstat not available"
echo ""
echo "=== Nginx config ==="
cat /etc/nginx/sites-enabled/default 2>/dev/null | head -40
