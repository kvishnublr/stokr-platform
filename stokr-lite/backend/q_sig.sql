SELECT id, symbol, side, left(reason, 80) as reason, status, source, created_at FROM signals ORDER BY id DESC LIMIT 15;
