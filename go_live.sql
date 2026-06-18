UPDATE trader_configs SET mode = 'LIVE' WHERE user_id = 1;
SELECT user_id, mode, capital, max_positions, min_share_price, max_share_price FROM trader_configs WHERE user_id = 1;
