-- PR-4: institutional execution lifecycle — normalized order states + append-only execution events.

UPDATE oms_orders SET state = 'VALIDATED' WHERE state = 'VALIDATING';
UPDATE oms_orders SET state = 'PENDING_SUBMISSION' WHERE state = 'QUEUED';
UPDATE oms_orders SET state = 'SUBMITTED' WHERE state = 'SENT';
UPDATE oms_orders SET state = 'ACCEPTED' WHERE state = 'ACKNOWLEDGED';
UPDATE oms_orders SET state = 'PARTIALLY_FILLED' WHERE state = 'PARTIAL_FILL';

ALTER TABLE oms_orders ALTER COLUMN state TYPE VARCHAR(64);

CREATE TABLE IF NOT EXISTS oms_execution_events (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    user_id UUID NOT NULL,
    order_id UUID NOT NULL REFERENCES oms_orders (id),
    signal_id UUID,
    backtest_run_id UUID,
    correlation_id VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    event_payload_json TEXT NOT NULL,
    stream_sequence BIGINT
);

CREATE INDEX IF NOT EXISTS idx_oms_execution_events_order ON oms_execution_events (order_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_oms_execution_events_run ON oms_execution_events (backtest_run_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_oms_execution_events_type ON oms_execution_events (event_type) WHERE deleted = FALSE;
