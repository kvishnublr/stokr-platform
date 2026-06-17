#!/bin/bash
set -e
cd /root/stokr-lite
git pull origin Release_v6
cd backend
mvn package -DskipTests -q
JAR=$(ls target/stokr-lite-*.jar | head -1)
pkill -f 'stokr-lite.*8070' 2>/dev/null || true
sleep 2
nohup java -jar "$JAR" \
  --server.port=8070 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/stokr_lite \
  --spring.datasource.username=stokr \
  --spring.datasource.password=root123 \
  --jwt.secret=stokr-lite-production-secret-key-that-is-at-least-256-bits-long \
  --stokr.ui.base-url=http://173.249.55.84:8082 \
  > /root/stokr-lite/app.log 2>&1 &
echo "Started with PID: $!"
