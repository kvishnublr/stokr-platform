#!/bin/bash
for i in 1 2 3 4 5 6 7 8 9 10; do
  echo "=== Attempt $i ==="
  result=$(docker exec stokr-api curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/strategies/catalog 2>&1)
  echo "HTTP: $result"
  if [ "$result" != "000" ]; then
    echo "=== Response ==="
    docker exec stokr-api curl -s http://localhost:8080/api/strategies/catalog 2>&1 | head -100
    break
  fi
  sleep 10
done
