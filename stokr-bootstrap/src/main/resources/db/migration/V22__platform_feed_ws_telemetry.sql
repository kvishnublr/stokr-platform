-- Runtime telemetry from in-process Zerodha WebSocket (platform market feed plane).

ALTER TABLE platform_broker_feed_sessions
    ADD COLUMN IF NOT EXISTS last_packet_at TIMESTAMPTZ;

ALTER TABLE platform_broker_feed_sessions
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ;

ALTER TABLE platform_broker_feed_sessions
    ADD COLUMN IF NOT EXISTS disconnect_reason VARCHAR(512);

ALTER TABLE platform_broker_feed_sessions
    ADD COLUMN IF NOT EXISTS reconnecting BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE platform_broker_feed_sessions
    ADD COLUMN IF NOT EXISTS tick_processing_latency_ms INT;
