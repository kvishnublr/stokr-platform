-- The payoff chart now shows the contract expiry a position was actually traded on -- a user
-- comparing our numbers against a different expiry on another tool saw wildly different
-- premiums and assumed a pricing bug. live_positions had no expiry field at all until now.
ALTER TABLE live_positions ADD COLUMN IF NOT EXISTS expiry_date DATE;
