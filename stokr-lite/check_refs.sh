curl -sk https://stokr.in/assets/index-DctqQVMR.js 2>/dev/null | grep -o 'assets/[^"]*\.js' | sort -u > /tmp/refs.txt
ls /opt/stokr/ui/assets/*.js | sed 's|/opt/stokr/ui/||' | sort -u > /tmp/actual.txt
echo "=== REFERENCED BUT MISSING ==="
comm -23 /tmp/refs.txt /tmp/actual.txt
echo "=== DONE ==="
