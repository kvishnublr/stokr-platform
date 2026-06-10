-- V110: Repair orphan tables to match BaseEntity columns
--
-- V105 (orphan_classification_results) and V106 (orphan_review_tasks,
-- orphan_review_approvals) created their tables without the full set of
-- BaseEntity audit columns (deleted / version / updated_at). The JPA entities
-- extend com.stokr.common.domain.BaseEntity, so Hibernate schema-validation
-- fails on boot with "missing column [deleted]" (etc.).
--
-- Add the missing columns idempotently so this runs cleanly on both the
-- existing production database and any freshly-migrated database.

ALTER TABLE orphan_classification_results
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE orphan_review_tasks
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE orphan_review_approvals
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
