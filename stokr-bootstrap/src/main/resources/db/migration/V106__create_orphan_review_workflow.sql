-- V106: Create Orphan Review Workflow Tables
-- Creates operator review tasks and approval history

CREATE TABLE IF NOT EXISTS orphan_review_tasks (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    orphan_id UUID NOT NULL,
    classification_id UUID NOT NULL,

    status VARCHAR(32) NOT NULL,
    assigned_operator_id UUID,
    priority VARCHAR(32),

    due_date TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,

    symbol VARCHAR(64) NOT NULL,
    broker_quantity NUMERIC(24,8) NOT NULL,
    classification_type VARCHAR(32) NOT NULL,
    evidence_score INT NOT NULL,
    confidence_level VARCHAR(32) NOT NULL,

    operator_notes TEXT,
    do_not_touch_reason TEXT,

    CONSTRAINT fk_review_orphan FOREIGN KEY (orphan_id) REFERENCES orphan_detected_positions(id),
    CONSTRAINT fk_review_classification FOREIGN KEY (classification_id) REFERENCES orphan_classification_results(id),
    CONSTRAINT fk_review_operator FOREIGN KEY (assigned_operator_id) REFERENCES auth_users(id)
);

CREATE INDEX IF NOT EXISTS idx_review_status ON orphan_review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_review_assigned ON orphan_review_tasks(assigned_operator_id);
CREATE INDEX IF NOT EXISTS idx_review_due_date ON orphan_review_tasks(due_date);
CREATE INDEX IF NOT EXISTS idx_review_priority ON orphan_review_tasks(priority DESC);
CREATE INDEX IF NOT EXISTS idx_review_do_not_touch ON orphan_review_tasks(status) WHERE status = 'DO_NOT_TOUCH';

CREATE TABLE IF NOT EXISTS orphan_review_approvals (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,

    orphan_id UUID NOT NULL,
    review_task_id UUID NOT NULL,
    operator_id UUID NOT NULL,

    approved_classification VARCHAR(32) NOT NULL,
    operator_notes TEXT,
    decision_timestamp TIMESTAMPTZ NOT NULL,

    classification_before VARCHAR(32),
    evidence_score_before INT,
    classification_after VARCHAR(32),
    evidence_score_after INT,

    approval_reason TEXT,

    CONSTRAINT fk_approval_orphan FOREIGN KEY (orphan_id) REFERENCES orphan_detected_positions(id),
    CONSTRAINT fk_approval_task FOREIGN KEY (review_task_id) REFERENCES orphan_review_tasks(id),
    CONSTRAINT fk_approval_operator FOREIGN KEY (operator_id) REFERENCES auth_users(id)
);

CREATE INDEX IF NOT EXISTS idx_approval_operator ON orphan_review_approvals(operator_id);
CREATE INDEX IF NOT EXISTS idx_approval_date ON orphan_review_approvals(decision_timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_approval_orphan ON orphan_review_approvals(orphan_id);
