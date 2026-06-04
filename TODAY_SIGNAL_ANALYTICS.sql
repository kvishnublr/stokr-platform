-- TODAY'S SIGNAL ANALYTICS (2026-06-04)
-- Query to analyze signals generated today with confidence > 70

-- 1. SUMMARY: Total signals today above 70 confidence
SELECT
    COUNT(*) as "Total Signals (>70 confidence)",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Hit Target",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "Hit StopLoss",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0) as "Target Hit %",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0) as "SL Hit %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL;

-- 2. DETAILED: By Strategy
SELECT
    strategy_name,
    COUNT(*) as "Count",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Target Hits",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SL Hits",
    ROUND(SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0), 2) as "Target %",
    ROUND(SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0), 2) as "SL %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
GROUP BY strategy_name
ORDER BY COUNT(*) DESC;

-- 3. DETAILED: By Symbol
SELECT
    symbol,
    COUNT(*) as "Count",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Target Hits",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SL Hits",
    ROUND(SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0), 2) as "Target %",
    ROUND(SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0), 2) as "SL %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
GROUP BY symbol
ORDER BY COUNT(*) DESC
LIMIT 20;

-- 4. CONFIDENCE DISTRIBUTION
SELECT
    CASE
        WHEN confidence_score >= 90 THEN '90-100 (Very High)'
        WHEN confidence_score >= 80 THEN '80-89 (High)'
        WHEN confidence_score >= 70 THEN '70-79 (Good)'
        WHEN confidence_score >= 60 THEN '60-69 (Moderate)'
        ELSE 'Below 60'
    END as "Confidence Bracket",
    COUNT(*) as "Count",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Target Hits",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SL Hits",
    ROUND(SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) * 100.0 /
        NULLIF(COUNT(*), 0), 2) as "Target %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND deleted = FALSE
    AND backtest_run_id IS NULL
GROUP BY "Confidence Bracket"
ORDER BY
    CASE
        WHEN "Confidence Bracket" = '90-100 (Very High)' THEN 1
        WHEN "Confidence Bracket" = '80-89 (High)' THEN 2
        WHEN "Confidence Bracket" = '70-79 (Good)' THEN 3
        WHEN "Confidence Bracket" = '60-69 (Moderate)' THEN 4
        ELSE 5
    END;

-- 5. TOP PERFORMERS (Confidence > 70 that hit target)
SELECT
    created_at AT TIME ZONE 'Asia/Kolkata' as "Time",
    strategy_name,
    symbol,
    ROUND(confidence_score::NUMERIC, 2) as "Confidence",
    entry_price,
    target_price,
    exit_price,
    ROUND(realized_pnl::NUMERIC, 2) as "PNL",
    hit_target as "Target Hit",
    hit_stoploss as "SL Hit"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
    AND hit_target = TRUE
ORDER BY created_at DESC
LIMIT 15;

-- 6. BOTTOM PERFORMERS (Confidence > 70 that hit SL)
SELECT
    created_at AT TIME ZONE 'Asia/Kolkata' as "Time",
    strategy_name,
    symbol,
    ROUND(confidence_score::NUMERIC, 2) as "Confidence",
    entry_price,
    stop_price,
    exit_price,
    ROUND(realized_pnl::NUMERIC, 2) as "PNL",
    hit_target as "Target Hit",
    hit_stoploss as "SL Hit"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
    AND hit_stoploss = TRUE
ORDER BY created_at DESC
LIMIT 15;

-- 7. STILL OPEN (No target or SL hit yet)
SELECT
    COUNT(*) as "Still Open",
    COUNT(DISTINCT symbol) as "Unique Symbols"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
    AND hit_target = FALSE
    AND hit_stoploss = FALSE;
