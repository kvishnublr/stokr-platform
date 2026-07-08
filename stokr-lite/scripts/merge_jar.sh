#!/bin/bash
set -e

# Strategy files to update from new JAR
STRATEGY_CLASSES=(
  "BOOT-INF/classes/com/stokr/strategy/Ema50DistanceStrategy.class"
  "BOOT-INF/classes/com/stokr/strategy/ThreeRedDaysStrategy.class"
  "BOOT-INF/classes/com/stokr/engine/BacktestController.class"
)

# 1. Extract new JAR to get updated classes
rm -rf /tmp/new_jar_extract
mkdir -p /tmp/new_jar_extract
cd /tmp/new_jar_extract
jar xf /tmp/stokr-lite-new.jar

# 2. Replace strategy classes in existing JAR
cd /tmp/jar_extract
for cls in "${STRATEGY_CLASSES[@]}"; do
  src="/tmp/new_jar_extract/$cls"
  if [ -f "$src" ]; then
    cp "$src" "$cls"
    echo "Updated: $cls"
  else
    echo "WARNING: $cls not found in new JAR"
  fi
done

# 3. Re-pack into deployed JAR
jar uf /opt/stokr/stokr-lite.jar \
  BOOT-INF/classes/com/stokr/strategy/Ema50DistanceStrategy.class \
  BOOT-INF/classes/com/stokr/strategy/ThreeRedDaysStrategy.class \
  BOOT-INF/classes/com/stokr/engine/BacktestController.class

echo "JAR updated with new strategy classes"

# 4. Restart service
systemctl restart stokr-lite
sleep 3
systemctl status stokr-lite --no-pager | head -15
