#!/bin/bash
set -e

echo "=== Deploying backend changes ==="
cd /opt/stokr

# Copy updated Java files into the source tree
BACKEND_SRC=/opt/stokr/stokr-backend
cp /tmp/SignalController.java $BACKEND_SRC/src/main/java/com/stokr/engine/SignalController.java
cp /tmp/MarketDataController.java $BACKEND_SRC/src/main/java/com/stokr/marketdata/MarketDataController.java
cp /tmp/SecurityConfig.java $BACKEND_SRC/src/main/java/com/stokr/config/SecurityConfig.java

echo "Compiling backend..."
cd $BACKEND_SRC
mvn compile -q 2>&1 | tail -5

echo "Packaging backend..."
mvn package -q -DskipTests 2>&1 | tail -5

echo "Deploying new JAR..."
systemctl stop stokr-lite
cp target/stokr-lite.jar /opt/stokr/stokr-lite.jar
systemctl start stokr-lite
echo "Backend deployed and restarted."

echo ""
echo "=== Waiting for backend to start ==="
sleep 5
systemctl status stokr-lite --no-pager | head -15
