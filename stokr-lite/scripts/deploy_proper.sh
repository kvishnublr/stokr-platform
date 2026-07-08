#!/bin/bash
set -e

echo "=== Step 1: Prepare frontend dist ==="
TMPDIR=/tmp/stokr-deploy
rm -rf $TMPDIR
mkdir -p $TMPDIR/dist

# Frontend build will be uploaded separately, for now just backend
echo "=== Step 2: Extract JAR ==="
cd $TMPDIR
mkdir -p extracted
cd extracted
jar xf /opt/stokr/stokr-lite.jar

echo "=== Step 3: Replace compiled classes ==="
CLASSES_SRC=/opt/stokr/stokr-platform/stokr-lite/backend/target/classes
# Remove old classes for changed files
rm -f $TMPDIR/extracted/BOOT-INF/classes/com/stokr/engine/SignalController.class
rm -f $TMPDIR/extracted/BOOT-INF/classes/com/stokr/marketdata/MarketDataController.class
rm -f $TMPDIR/extracted/BOOT-INF/classes/com/stokr/config/SecurityConfig.class
rm -f $TMPDIR/extracted/BOOT-INF/classes/com/stokr/strategy/Strategy.class

# Copy new class files
cp $CLASSES_SRC/com/stokr/engine/SignalController.class $TMPDIR/extracted/BOOT-INF/classes/com/stokr/engine/
cp $CLASSES_SRC/com/stokr/marketdata/MarketDataController.class $TMPDIR/extracted/BOOT-INF/classes/com/stokr/marketdata/
cp $CLASSES_SRC/com/stokr/config/SecurityConfig.class $TMPDIR/extracted/BOOT-INF/classes/com/stokr/config/
cp $CLASSES_SRC/com/stokr/strategy/Strategy.class $TMPDIR/extracted/BOOT-INF/classes/com/stokr/strategy/

echo "=== Step 4: Replace frontend static files ==="
if [ -d /tmp/frontend-dist ]; then
    rm -rf $TMPDIR/extracted/BOOT-INF/classes/static/*
    cp -r /tmp/frontend-dist/* $TMPDIR/extracted/BOOT-INF/classes/static/
    echo "Frontend assets replaced"
fi

echo "=== Step 5: Repackage JAR ==="
cd $TMPDIR/extracted
# Use jar uf to update the existing jar by recreating from extracted
# Since jar uf with relative paths failed before, use jar cf with MANIFEST
jar cf /opt/stokr/stokr-lite.jar .

echo "=== Step 6: Restart ==="
systemctl restart stokr-lite
echo "Done. Waiting for startup..."
sleep 10
curl -s http://localhost:8081/api/signals/stats --max-time 5 || echo "WARNING: Backend not responding yet"

# Cleanup
rm -rf $TMPDIR
