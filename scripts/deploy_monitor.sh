#!/bin/bash
# Deploy exit monitor script + cron

echo "=== COPYING MONITOR SCRIPT ==="
cp /tmp/exit_monitor.py /opt/stokr/scripts/exit_monitor.py
chmod +x /opt/stokr/scripts/exit_monitor.py

echo "=== INSTALLING CRON ==="
# Remove old entry if exists
crontab -l 2>/dev/null | grep -v exit_monitor | crontab -
# Add new entry — runs every 60s
(crontab -l 2>/dev/null; echo "* * * * * /usr/bin/python3 /opt/stokr/scripts/exit_monitor.py >> /var/log/stokr-exit-monitor.log 2>&1") | crontab -

echo "=== TEST RUN ==="
/usr/bin/python3 /opt/stokr/scripts/exit_monitor.py
echo ""
echo "=== CRONTAB ==="
crontab -l | grep exit_monitor
echo ""
echo "=== LOG TAIL ==="
tail -5 /var/log/stokr-exit-monitor.log 2>/dev/null || echo "(new log)"
