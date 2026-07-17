#!/bin/bash

echo "=== DOCKER EVENTS (restarts/stops) ==="
docker events --since 2026-07-12T00:00:00 --until 2026-07-14T00:00:00 --filter container=stokr-lite-backend --format '{{.Time}} {{.Action}}' 2>/dev/null | head -20

echo ""
echo "=== CONTAINER INSPECT ==="
docker inspect stokr-lite-backend --format='
StartedAt: {{.State.StartedAt}}
FinishedAt: {{.State.FinishedAt}}
RestartCount: {{.RestartCount}}
RestartPolicy: {{.HostConfig.RestartPolicy.Name}}
MaxRestart: {{.HostConfig.RestartPolicy.MaximumRetryCount}}
OOMKilled: {{.State.OOMKilled}}
ExitCode: {{.State.ExitCode}}
Status: {{.State.Status}}
Platform: {{.Platform}}
' 

echo ""
echo "=== ALL DOCKER LOGS BEFORE RESTART (12:00) ==="
docker logs stokr-lite-backend 2>&1 | grep -E '2026-07-13T0[0-9]:|2026-07-13T1[01]:' | head -30

echo ""
echo "=== LAST LOGS BEFORE CURRENT STARTUP ==="
docker logs stokr-lite-backend 2>&1 | head -5

echo ""
echo "=== DOCKER COMPOSE CONFIG ==="
cat /opt/stokr/stokr-platform/stokr-lite/docker-compose.yml 2>/dev/null | head -40

echo ""
echo "=== SYSTEM LOGS - DOCKER/RESTART ==="
journalctl -u docker --since "2026-07-13 09:00" --until "2026-07-13 13:00" --no-pager 2>/dev/null | grep -i "stokr\|restart\|kill\|oom\|stop\|start" | tail -20 || echo "no journalctl"

echo ""
echo "=== OOM/KILLS ==="
dmesg 2>/dev/null | grep -i "oom\|kill\|out of memory" | tail -10 || echo "no dmesg access"

echo ""
echo "=== CRON JOBS THAT MIGHT RESTART ==="
crontab -l 2>&1 | grep -i "restart\|docker\|stokr\|deploy"

echo ""
echo "=== CRONTAB 8:30 TOKEN REFRESH LOG ==="
tail -30 /var/log/stokr-token-refresh.log 2>/dev/null || echo "no log"
