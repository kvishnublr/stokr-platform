SELECT vendor_code, connection_state, websocket_state, 
       last_tick_at, last_packet_at, last_heartbeat_at,
       ticks_per_sec, subscription_count, reconnect_count,
       feed_lag_ms, disconnect_reason
FROM platform_broker_feed_sessions
WHERE deleted = FALSE
ORDER BY vendor_code;
