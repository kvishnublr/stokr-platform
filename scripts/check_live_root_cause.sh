#!/bin/bash
echo "=== Tables related to halt/state ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND (table_name LIKE '%halt%' OR table_name LIKE '%suspend%' OR table_name LIKE '%operational%' OR table_name LIKE '%execution_state%' OR table_name LIKE '%session_state%');"

echo ""
echo "=== Recent halt events ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT created_at AT TIME ZONE 'Asia/Kolkata' as ist, topic, actor FROM operational_audit_events WHERE topic LIKE '%HALT%' OR topic LIKE '%SUSPEND%' OR topic LIKE '%STOP%' ORDER BY created_at DESC LIMIT 5;"

echo ""
echo "=== signal_id on recent PAPER orders ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT signal_id IS NOT NULL as has_signal, count(*) FROM oms_orders WHERE execution_mode='PAPER' AND created_at > NOW() - INTERVAL '30 minutes' GROUP BY has_signal;"

echo ""
echo "=== signal_id on recent orders (sample) ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT signal_id, substring(cast(signal_id as text), 1, 8) as sig_prefix, symbol, side, execution_mode FROM oms_orders WHERE created_at > NOW() - INTERVAL '30 minutes' LIMIT 5;"

echo ""
echo "=== Verify: 4 LIVE legs from monitor ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT o.symbol, o.side, o.strategy_key, o.state, o.created_at AT TIME ZONE 'Asia/Kolkata' as ist FROM oms_orders o WHERE o.execution_mode='LIVE' AND o.state='FILLED' AND o.paired_order_id IS NULL ORDER BY o.created_at DESC;"

echo ""
echo "=== All orders last 5 min ==="
docker exec -i stokr-postgres psql -U postgres stokr_platform -t -c "SELECT execution_mode, state, count(*) FROM oms_orders WHERE created_at > NOW() - INTERVAL '5 minutes' GROUP BY execution_mode, state ORDER BY execution_mode, state;"
