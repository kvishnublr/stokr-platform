-- TODAY'S SIGNALS: CONFIDENCE-ONLY ANALYSIS
-- Independent of strategy - only looking at confidence score > 70

-- ============================================================
-- 1. THE MOST IMPORTANT: Summary Stats
-- ============================================================
SELECT
    COUNT(*) as "TOTAL SIGNALS (>70 confidence)",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "HIT TARGET",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "HIT STOPLOSS",
    SUM(CASE WHEN hit_target = FALSE AND hit_stoploss = FALSE THEN 1 ELSE 0 END) as "STILL OPEN",

    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) /
        NULLIF(COUNT(*), 0), 2) as "TARGET HIT %",

    ROUND(100.0 * SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) /
        NULLIF(COUNT(*), 0), 2) as "SL HIT %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL;

-- ============================================================
-- 2. All signals with confidence > 70 (sorted by result)
-- ============================================================
SELECT
    created_at AT TIME ZONE 'Asia/Kolkata' as "Generated",
    symbol,
    strategy_name,
    ROUND(confidence_score::NUMERIC, 2) as "Confidence",
    entry_price as "Entry",
    target_price as "Target",
    stop_price as "SL",
    exit_price as "Exit",
    ROUND(realized_pnl::NUMERIC, 2) as "PNL",
    CASE
        WHEN hit_target THEN '✅ TARGET HIT'
        WHEN hit_stoploss THEN '❌ SL HIT'
        ELSE '⏳ OPEN'
    END as "Result"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
ORDER BY
    CASE
        WHEN hit_target THEN 1
        WHEN hit_stoploss THEN 2
        ELSE 3
    END,
    created_at DESC;

-- ============================================================
-- 3. Breakdown by confidence brackets (all today's signals)
-- ============================================================
SELECT
    CASE
        WHEN confidence_score >= 90 THEN '🔥 90-100 (Very High)'
        WHEN confidence_score >= 80 THEN '🟢 80-89 (High)'
        WHEN confidence_score >= 70 THEN '🟡 70-79 (Good)'
        WHEN confidence_score >= 60 THEN '🟠 60-69 (Moderate)'
        ELSE '🔴 Below 60'
    END as "Confidence Bracket",
    COUNT(*) as "Total Signals",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Target Hits",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SL Hits",
    SUM(CASE WHEN hit_target = FALSE AND hit_stoploss = FALSE THEN 1 ELSE 0 END) as "Still Open",
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) /
        NULLIF(COUNT(*), 0), 1) as "Hit Rate %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND deleted = FALSE
    AND backtest_run_id IS NULL
GROUP BY "Confidence Bracket"
ORDER BY
    CASE
        WHEN confidence_score >= 90 THEN 1
        WHEN confidence_score >= 80 THEN 2
        WHEN confidence_score >= 70 THEN 3
        WHEN confidence_score >= 60 THEN 4
        ELSE 5
    END;

-- ============================================================
-- 4. Statistics for different confidence thresholds
-- ============================================================
SELECT
    'Above 70' as "Threshold",
    COUNT(*) as "Signals",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Targets",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SLs",
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as "Hit %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND deleted = FALSE
    AND backtest_run_id IS NULL
UNION ALL
SELECT
    'Above 75' as "Threshold",
    COUNT(*) as "Signals",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Targets",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SLs",
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as "Hit %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 75
    AND deleted = FALSE
    AND backtest_run_id IS NULL
UNION ALL
SELECT
    'Above 80' as "Threshold",
    COUNT(*) as "Signals",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Targets",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SLs",
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as "Hit %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 80
    AND deleted = FALSE
    AND backtest_run_id IS NULL
UNION ALL
SELECT
    'Above 85' as "Threshold",
    COUNT(*) as "Signals",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Targets",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SLs",
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as "Hit %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 85
    AND deleted = FALSE
    AND backtest_run_id IS NULL
UNION ALL
SELECT
    'Above 90' as "Threshold",
    COUNT(*) as "Signals",
    SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) as "Targets",
    SUM(CASE WHEN hit_stoploss THEN 1 ELSE 0 END) as "SLs",
    ROUND(100.0 * SUM(CASE WHEN hit_target THEN 1 ELSE 0 END) / NULLIF(COUNT(*), 0), 1) as "Hit %"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 90
    AND deleted = FALSE
    AND backtest_run_id IS NULL
ORDER BY
    CASE
        WHEN "Threshold" = 'Above 90' THEN 1
        WHEN "Threshold" = 'Above 85' THEN 2
        WHEN "Threshold" = 'Above 80' THEN 3
        WHEN "Threshold" = 'Above 75' THEN 4
        ELSE 5
    END;

-- ============================================================
-- 5. Winning signals (that hit target)
-- ============================================================
SELECT
    COUNT(*) as "Total Winning Signals",
    COUNT(DISTINCT symbol) as "Unique Symbols Hit",
    ROUND(AVG(realized_pnl::NUMERIC), 2) as "Avg PNL Per Signal",
    ROUND(SUM(realized_pnl::NUMERIC), 2) as "Total PNL from Winners",
    ROUND(AVG(confidence_score::NUMERIC), 2) as "Avg Confidence of Winners"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND hit_target = TRUE
    AND deleted = FALSE
    AND backtest_run_id IS NULL;

-- ============================================================
-- 6. Losing signals (that hit SL)
-- ============================================================
SELECT
    COUNT(*) as "Total Losing Signals",
    COUNT(DISTINCT symbol) as "Unique Symbols Hit SL",
    ROUND(AVG(realized_pnl::NUMERIC), 2) as "Avg Loss Per Signal",
    ROUND(SUM(realized_pnl::NUMERIC), 2) as "Total Loss from SLs",
    ROUND(AVG(confidence_score::NUMERIC), 2) as "Avg Confidence of SLs"
FROM strategy_signals
WHERE
    DATE(created_at AT TIME ZONE 'Asia/Kolkata') = CURRENT_DATE AT TIME ZONE 'Asia/Kolkata'
    AND confidence_score > 70
    AND hit_stoploss = TRUE
    AND deleted = FALSE
    AND backtest_run_id IS NULL;
