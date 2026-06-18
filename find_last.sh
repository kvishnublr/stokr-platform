#!/bin/bash
# Find the log lines right before each restart (the last messages before exit)
echo "=== Last 3 distinct patterns before each exit ==="
docker logs stokr-api 2>&1 | grep -E "Tomcat started|Started Stokr|RabbitAdmin|app.signal|application.shutdown|Shutting|destroy|shutdown" | tail -20

echo ""
echo "=== Lines containing 'exiting' or 'shutdown' ==="
docker logs stokr-api 2>&1 | grep -i "exiting\|shutdown\|shutting\|stopping\|stop()" | tail -10
