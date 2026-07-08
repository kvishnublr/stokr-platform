#!/bin/bash
set -e

JAR=/opt/stokr/stokr-lite.jar
CLASSES_SRC=/opt/stokr/stokr-platform/stokr-lite/backend/target/classes
TMPDIR=/tmp/stokr-deploy

# Safety backup
cp $JAR /opt/stokr/stokr-lite.jar.safe

rm -rf $TMPDIR
mkdir -p $TMPDIR

# Extract JAR
cd $TMPDIR
jar xf $JAR

# Verify manifest exists
if [ ! -f META-INF/MANIFEST.MF ]; then
    echo "ERROR: MANIFEST.MF lost! Restoring..."
    cp /opt/stokr/stokr-lite.jar.safe $JAR
    exit 1
fi

echo "Manifest OK. Updating backend classes..."
# Update only our changed classes
cp $CLASSES_SRC/com/stokr/engine/SignalController.class BOOT-INF/classes/com/stokr/engine/
cp $CLASSES_SRC/com/stokr/marketdata/MarketDataController.class BOOT-INF/classes/com/stokr/marketdata/
cp $CLASSES_SRC/com/stokr/config/SecurityConfig.class BOOT-INF/classes/com/stokr/config/
cp $CLASSES_SRC/com/stokr/strategy/Strategy.class BOOT-INF/classes/com/stokr/strategy/

echo "Updating frontend assets..."
if [ -d /tmp/frontend-dist ]; then
    rm -rf BOOT-INF/classes/static
    mkdir -p BOOT-INF/classes/static
    cp -r /tmp/frontend-dist/* BOOT-INF/classes/static/
    echo "Frontend replaced"
fi

echo "Repackaging JAR..."
# Recreate JAR preserving all entries
jar cf $JAR -C $TMPDIR .

echo "Verifying JAR..."
jar tf $JAR | grep -c "BOOT-INF" || { echo "ERROR: BOOT-INF missing"; cp /opt/stokr/stokr-lite.jar.safe $JAR; exit 1; }

# Restart
systemctl restart stokr-lite
echo "Restarted. Waiting..."
sleep 12
RESULT=$(curl -s http://localhost:8081/api/signals/stats --max-time 5)
echo "Health check: $RESULT"

if echo "$RESULT" | grep -q "total"; then
    echo "SUCCESS"
    rm -rf $TMPDIR
else
    echo "FAILED - restoring backup"
    cp /opt/stokr/stokr-lite.jar.safe /opt/stokr/stokr-lite.jar
    systemctl restart stokr-lite
fi
