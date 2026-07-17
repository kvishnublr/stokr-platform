import re

path = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java'
with open(path, 'r') as f:
    content = f.read()

old = '''            // EMA50D needs 50 candles, OB needs ~20 — load 100 extra days to be safe
            LocalDateTime warmupStartTime = startTime;
            if (DAILY_STRATEGIES.contains(resolvedPluginType)) {
                warmupStartTime = startTime.minusDays(150);
                log.info("Daily strategy warmup: loading from {} instead of {} (extra 150 days)", warmupStartTime, startTime);
            }'''

new = '''            // EMA50D needs 50 candles, OB needs ~20 — load 100 extra days to be safe
            final LocalDateTime warmupStartTime = DAILY_STRATEGIES.contains(resolvedPluginType) ? startTime.minusDays(150) : startTime;
            if (DAILY_STRATEGIES.contains(resolvedPluginType)) {
                log.info("Daily strategy warmup: loading from {} instead of {} (extra 150 days)", warmupStartTime, startTime);
            }'''

content = content.replace(old, new)
with open(path, 'w') as f:
    f.write(content)
print('Patched warmupStartTime to be effectively final')
