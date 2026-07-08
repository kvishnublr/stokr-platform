#!/bin/bash
set -e

# 1. Extract current JAR
rm -rf /tmp/jar_extract
mkdir -p /tmp/jar_extract
cd /tmp/jar_extract
jar xf /opt/stokr/stokr-lite.jar

# 2. Remove old static files
rm -rf BOOT-INF/classes/static/*

# 3. Copy new frontend files
mkdir -p /tmp/new_frontend_extract
cd /tmp/new_frontend_extract
tar xzf /tmp/frontend.tar.gz
cp -r * /tmp/jar_extract/BOOT-INF/classes/static/

# 4. Re-pack static files into JAR
cd /tmp/jar_extract
jar uf /opt/stokr/stokr-lite.jar BOOT-INF/classes/static/

echo "Frontend updated in JAR"
ls -la BOOT-INF/classes/static/
