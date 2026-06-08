#!/bin/bash
echo "=== WAITING FOR SIGNALS (3 min) ==="
for i in 1 2 3; do
  sleep 60
  echo "--- Minute $i ---"
  echo "Orders last min:"
  docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT execution_mode, state, count(*) FROM oms_orders WHERE created_at > NOW() - INTERVAL '1 minute' GROUP BY execution_mode, state ORDER BY execution_mode, state;"
  echo "Signals last min:"
  docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT count(*) FROM strategy_signals WHERE created_at > NOW() - INTERVAL '1 minute';"
  echo "Exit mon:"
  tail -1 /var/log/stokr-exit-monitor.log
done

echo ""
echo "=== FULL STATUS AFTER 3 MIN ==="
echo "--- Orders last 5 min ---"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT execution_mode, state, count(*) FROM oms_orders WHERE created_at > NOW() - INTERVAL '5 minutes' GROUP BY execution_mode, state ORDER BY execution_mode, state;"

echo "--- LIVE FILLED last 15 min ---"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, symbol, side, strategy_key FROM oms_orders WHERE execution_mode='LIVE' AND state='FILLED' AND created_at > NOW() - INTERVAL '15 minutes' ORDER BY created_at;"

echo "--- LIVE REJECTED last 15 min ---"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, symbol, side, reject_reason, strategy_key FROM oms_orders WHERE execution_mode='LIVE' AND state='REJECTED' AND created_at > NOW() - INTERVAL '15 minutes' ORDER BY created_at;"

echo "--- signal_id linkage last 5 min ---"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT signal_id IS NOT NULL as has_signal, count(*) FROM oms_orders WHERE created_at > NOW() - INTERVAL '5 minutes' GROUP BY has_signal;"

echo "--- Open positions ---"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT execution_mode, count(*) FROM oms_orders WHERE state='FILLED' AND paired_order_id IS NULL GROUP BY execution_mode;"

echo "--- Exits last 5 min ---"
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT outcome_status, count(*) FROM strategy_signals WHERE updated_at > NOW() - INTERVAL '5 minutes' AND outcome_status IS NOT NULL AND outcome_status != '' GROUP BY outcome_status;"

echo "--- Exit monitor ---"
tail -5 /var/log/stokr-exit-monitor.log
