INSERT INTO trader_configs (user_id, mode, capital, max_positions, min_share_price, max_share_price, stop_loss_pct, target_pct, max_daily_loss, min_trade_gap_minutes, max_consecutive_losses, enabled, created_at, updated_at)
VALUES (1, 'LIVE', 15000, 3, 200, 3000, 0.2, 0.6, 225, 2, 3, true, NOW(), NOW())
ON CONFLICT (user_id) DO UPDATE SET mode = 'LIVE';

SELECT user_id, mode, capital, max_positions, min_share_price, max_share_price FROM trader_configs WHERE user_id = 1;
