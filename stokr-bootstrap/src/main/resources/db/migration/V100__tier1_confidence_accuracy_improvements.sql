-- V100: Tier 1 Signal Accuracy Improvements
-- Raises confidence threshold from 60/70 to 80 for better signal quality
-- Disables synthetic fallback signals, requires real order flow data
-- Adds liquidity gate (score >= 70) and risk:reward ratio requirement (>= 2.0)

-- Update all enabled confidence strategy configs to threshold 80
UPDATE confidence_strategy_config
SET min_confidence_threshold = 80, updated_at = CURRENT_TIMESTAMP
WHERE enabled = TRUE AND min_confidence_threshold < 80;

-- Log the update
INSERT INTO confidence_signal_summary (summary_date, threshold_80_count, created_at, updated_at)
SELECT CURRENT_DATE, COUNT(*), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM confidence_strategy_config
WHERE enabled = TRUE AND min_confidence_threshold = 80
ON CONFLICT (summary_date) DO UPDATE SET
    threshold_80_count = threshold_80_count + EXCLUDED.threshold_80_count,
    updated_at = CURRENT_TIMESTAMP;
