#!/bin/bash
set -e

echo "======================================"
echo "=== Stokr.in Deployment Verification ==="
echo "======================================"

echo ""
echo "[1] Backend Health (port 8070):"
curl -s http://localhost:8070/actuator/health
echo ""

echo ""
echo "[2] API via nginx (port 8082):"
curl -s -o /dev/null -w "  /api/market/status: HTTP %{http_code}\n" http://localhost:8082/api/market/status || true
curl -s -o /dev/null -w "  /api/auth/login: HTTP %{http_code}\n" http://localhost:8082/api/auth/login || true

echo ""
echo "[3] Frontend via nginx (port 8082):"
curl -s -o /dev/null -w "  HTTP %{http_code}\n" http://localhost:8082/

echo ""
echo "[4] stokr.in via Caddy (localhost):"
curl -s -o /dev/null -w "  HTTP %{http_code}\n" -L http://localhost:80/ -H "Host: stokr.in"

echo ""
echo "[5] new.stokr.in via Caddy (localhost):"
curl -s -o /dev/null -w "  HTTP %{http_code}\n" -L http://localhost:80/ -H "Host: new.stokr.in"

echo ""
echo "[6] Deploy scripts updated:"
grep -l "stokr.in" /root/stokr-lite/deploy.sh /root/stokr-lite/redeploy.sh /root/stokr-lite/final-deploy.sh /root/stokr-lite/deploy-server.sh 2>/dev/null | while read f; do echo "  OK: $f"; done

echo ""
echo "[7] Running processes:"
ss -tlnp | grep -E '8070|8082' || netstat -tlnp | grep -E '8070|8082' || echo "  Ports check via ss/netstat"

echo ""
echo "======================================"
echo "=== Verification Complete ==="
echo "======================================"
