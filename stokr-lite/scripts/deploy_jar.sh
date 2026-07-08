#!/bin/bash
set -e
cd /opt/stokr/stokr-platform/stokr-lite/backend

# Create temp dir for repackaging
TMPJAR=/tmp/stokr-repack
rm -rf $TMPJAR
mkdir -p $TMPJAR

# Extract existing jar
cd $TMPJAR
jar xf /opt/stokr/stokr-lite.jar

# Copy new class files from target/classes into the extracted BOOT-INF/classes
cp -r /opt/stokr/stokr-platform/stokr-lite/backend/target/classes/* $TMPJAR/BOOT-INF/classes/

# Remove old JAR, create new one
rm -f /opt/stokr/stokr-lite.jar
cd $TMPJAR
jar cf /opt/stokr/stokr-lite.jar .

# Cleanup
rm -rf $TMPJAR

# Restart
systemctl restart stokr-lite
echo "Deployed and restarted"
