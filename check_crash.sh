#!/bin/bash
echo "=== Crash logs ==="
docker logs stokr-api 2>&1 | grep -E "Exception|Error creating bean|Application run failed|UnsatisfiedDependency" | tail -10

echo ""
echo "=== Cron or scheduler crash ==="
docker logs stokr-api 2>&1 | grep -i "scheduler\|scheduled\|cron" | grep -i "error\|exception\|fail" | tail -5

echo ""
echo "=== OOM killer check ==="
docker inspect stokr-api 2>/dev/null | grep -i "oom\|memory\|OOMKilled"

echo ""
echo "=== Container exit code ==="
docker inspect stokr-api --format '{{.State.ExitCode}} {{.State.FinishedAt}}'
