#!/bin/bash
set -e

CLASSES_SRC=/opt/stokr/stokr-platform/stokr-lite/backend/target/classes

# Backup current JAR
cp /opt/stokr/stokr-lite.jar /opt/stokr/stokr-lite.jar.bak2

# Use zip -u to update specific class files inside the JAR
cd $CLASSES_SRC
zip -u /opt/stokr/stokr-lite.jar \
    com/stokr/engine/SignalController.class \
    com/stokr/marketdata/MarketDataController.class \
    com/stokr/config/SecurityConfig.class \
    com/stokr/strategy/Strategy.class

echo "JAR updated via zip"

# Replace frontend static assets
if [ -d /tmp/frontend-dist ]; then
    echo "Updating frontend assets..."
    cd /tmp/frontend-dist
    # Find and remove all old static assets from jar
    for f in $(find . -type f); do
        zip -d /opt/stokr/stokr-lite.jar "BOOT-INF/classes/static/$f" 2>/dev/null || true
    done
    # Add new assets
    zip -r -u /opt/stokr/stokr-lite.jar BOOT-INF/classes/static/
    echo "Frontend assets updated"
fi

# Restart
systemctl restart stokr-lite
echo "Restarted"
sleep 10
curl -s http://localhost:8081/api/signals/stats --max-time 5
