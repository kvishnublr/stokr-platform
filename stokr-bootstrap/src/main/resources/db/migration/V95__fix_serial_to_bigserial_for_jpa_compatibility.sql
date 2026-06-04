-- V95: Fix SERIAL -> BIGSERIAL for tables whose JPA entities use @Id Long
-- Hibernate schema-validation found INTEGER (from SERIAL) but expected BIGINT.
-- Converting column type to BIGINT + widening the backing sequence.

ALTER TABLE intelligence_scores ALTER COLUMN id TYPE BIGINT;
ALTER SEQUENCE IF EXISTS intelligence_scores_id_seq AS BIGINT;

ALTER TABLE confidence_scores ALTER COLUMN id TYPE BIGINT;
ALTER SEQUENCE IF EXISTS confidence_scores_id_seq AS BIGINT;

ALTER TABLE confidence_strategy_config ALTER COLUMN id TYPE BIGINT;
ALTER SEQUENCE IF EXISTS confidence_strategy_config_id_seq AS BIGINT;

ALTER TABLE confidence_signal_summary ALTER COLUMN id TYPE BIGINT;
ALTER SEQUENCE IF EXISTS confidence_signal_summary_id_seq AS BIGINT;
