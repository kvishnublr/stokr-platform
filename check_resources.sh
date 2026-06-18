#!/bin/bash
echo "=== Resource limits ==="
docker inspect stokr-api --format 'Memory: {{.HostConfig.Memory}}, Swap: {{.HostConfig.MemorySwap}}, CPUs: {{.HostConfig.NanoCpus}}'

echo ""
echo "=== Docker events ==="
docker events --since 5m --until now --filter container=stokr-api --format '{{.Type}} {{.Action}} {{.Status}}' 2>&1 | tail -10

echo ""
echo "=== JVM max memory ==="
docker exec stokr-api sh -c 'echo "Max Heap: $(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo unlimited)"' 2>/dev/null

echo ""
echo "=== OOM in dmesg ==="
dmesg 2>/dev/null | grep -i "oom\|killed" | tail -3 || echo "no dmesg access"

echo ""
echo "=== Checking if container.log exists ==="
ls -la /var/lib/docker/containers/$(docker inspect stokr-api --format '{{.ID}}')/*.log 2>/dev/null | head -3
