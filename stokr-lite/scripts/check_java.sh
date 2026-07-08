#!/bin/bash
set -e
echo "=== Java heap details ==="
jstat -gc $(pgrep -f 'stokr-lite.jar') 2>/dev/null
echo ""
echo "=== Current JVM flags ==="
jcmd $(pgrep -f 'stokr-lite.jar') VM.flags 2>/dev/null || echo "jcmd not available"
echo ""
echo "=== Env file JVM settings ==="
cat /etc/systemd/system/stokr-lite.service 2>/dev/null | grep -i java || echo "no service file"
echo ""
echo "=== Actual JVM command ==="
ps aux | grep stokr-lite | grep -v grep
