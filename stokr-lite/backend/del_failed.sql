DELETE FROM live_positions WHERE status IN ('FAILED','REJECTED') RETURNING id, status, underlying, strike;
