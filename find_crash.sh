#!/bin/bash
# Get all logs, extract unique lines between two timestamps from each restart
echo "=== Logs from second before exit (07:03:00 to 07:03:20) ==="
docker logs stokr-api 2>&1 | grep "07:03:0[0-9]\|07:03:1[0-7]" | tail -30

echo ""
echo "=== All lines with ERROR or WARN that aren't reconciliation ==="
docker logs stokr-api 2>&1 | grep -E "(ERROR|WARN)" | grep -v "ReconciliationSafety\|reconciliation" | sort -u | tail -20

echo ""
echo "=== RabbitMQ related ==="
docker logs stokr-api 2>&1 | grep -i "rabbit\|amqp" | grep -i "error\|fail\|exception" | tail -5

echo ""
echo "=== Autoheal logs ==="
docker logs stokr-autoheal 2>&1 | tail -20
