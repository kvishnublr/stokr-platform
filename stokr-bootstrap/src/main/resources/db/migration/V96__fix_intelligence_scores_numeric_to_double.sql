-- V96: Fix NUMERIC -> DOUBLE PRECISION for confidence_multiplier
-- Hibernate maps Double -> float(53) / double precision, but V93 used DECIMAL(4,2) (NUMERIC).
-- The column had no data, so no CAST risk.

ALTER TABLE intelligence_scores ALTER COLUMN confidence_multiplier TYPE DOUBLE PRECISION;
