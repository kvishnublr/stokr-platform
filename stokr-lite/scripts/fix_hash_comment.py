import re

path = '/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/marketdata/ZerodhaLiveDataScheduler.java'
with open(path, 'r') as f:
    content = f.read()

# Fix the line with # comment
content = content.replace(
    '@Scheduled(cron = "0 0 3 29 2 ?", zone = "Asia/Kolkata")  # DISABLED - preserve data',
    '// @Scheduled(cron = "0 0 3 29 2 ?", zone = "Asia/Kolkata")  // DISABLED - preserve data'
)

with open(path, 'w') as f:
    f.write(content)

print("Fixed")
