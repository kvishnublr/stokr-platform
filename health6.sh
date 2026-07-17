#!/bin/bash

echo "=== WHEN DID BACKEND START TODAY? ==="
docker logs stokr-lite-backend 2>&1 | grep -iE 'Started|Tomcat started|Application started|Initializing|SpringBoot|reconcileOnStartup|PositionReconciler|ExecutionEngine.*init' | tail -10

echo ""
echo "=== FULL STARTUP SEQUENCE ==="
docker logs stokr-lite-backend 2>&1 | grep -iE 'started|initializ|spring|boot|tomcat|context|servlet|deploy' | grep "2026-07-13" | tail -20

echo ""
echo "=== MARKET HOURS ACTIVITY (9:15-15:30) ==="
docker logs stokr-lite-backend 2>&1 | grep "2026-07-13" | awk '/T09:|T10:|T11:|T12:|T13:|T14:|T15:[0-2]/' | grep -viE 'Security|authentication|session|Filter' | tail -20

echo ""
echo "=== DOCKER RESTART HISTORY ==="
docker inspect stokr-lite-backend --format='{{.State.StartedAt}} RestartCount={{.RestartCount}} RestartPolicy={{.HostConfig.RestartPolicy.Name}}'

echo ""
echo "=== DEPLOYMENTS (simple query) ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, status, mode, broker_account_id FROM deployments;"

echo ""
echo "=== STRATEGY SIGNALS COUNT TODAY ==="
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT COUNT(*) as today_signals FROM strategy_signals WHERE created_at::date = '2026-07-13';"
