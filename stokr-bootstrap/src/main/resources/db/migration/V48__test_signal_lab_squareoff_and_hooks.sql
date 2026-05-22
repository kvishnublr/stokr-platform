ALTER TABLE admin_test_signal_runs
    ADD COLUMN IF NOT EXISTS auto_square_off_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS square_off_order_id UUID,
    ADD COLUMN IF NOT EXISTS square_off_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS square_off_completed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_admin_test_signal_runs_squareoff_due
    ON admin_test_signal_runs (auto_square_off_due_at)
    WHERE deleted = FALSE;
