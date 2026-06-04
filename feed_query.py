import json, urllib.request

d = json.dumps({"principal": "admin", "password": "password"}).encode()
r = urllib.request.Request("http://localhost:8080/api/auth/login", data=d, headers={"Content-Type": "application/json"}, method="POST")
resp = json.loads(urllib.request.urlopen(r).read().decode())
token = resp["data"]["accessToken"]

headers = {"Authorization": f"Bearer {token}"}

req = urllib.request.Request("http://localhost:8080/api/admin/settings/summary", headers=headers)
settings = json.loads(urllib.request.urlopen(req).read().decode())
s = settings["data"]
print(f"Feed State: {s.get('marketFeedState', 'N/A')}")
print(f"Subscriptions: {s.get('marketFeedSubscriptions', 'N/A')}")
print(f"Ticks/sec: {s.get('marketFeedTicksPerSec', 'N/A')}")
print(f"Last packet: {s.get('marketFeedLastPacket', 'N/A')}")

print("\n--- SQL for DB check ---")
print("""
-- Check platform broker feed sessions (production DB via docker)
SELECT vendor_code, connection_state, websocket_state, 
       last_tick_at, last_packet_at, last_heartbeat_at,
       ticks_per_sec, packets_per_sec, subscription_count,
       reconnect_count, feed_lag_ms, disconnect_reason
FROM platform_broker_feed_sessions
WHERE deleted = FALSE
ORDER BY vendor_code;

-- Check last feed health events
SELECT * FROM feed_health_events 
ORDER BY created_at DESC LIMIT 10;

-- Check strategy_runtime_health
SELECT strategy_key, session_date, scan_count, signal_count,
       reject_count, last_heartbeat_at, state
FROM strategy_runtime_health
WHERE session_date = CURRENT_DATE
ORDER BY strategy_key;

-- Check market_data_coverage for stale symbols
SELECT symbol, timeframe, stale_state, replay_ready, scanner_ready,
       updated_at
FROM market_data_coverage
WHERE stale_state IS NOT NULL AND stale_state != 'OK'
ORDER BY updated_at DESC LIMIT 20;
""")
