INSERT INTO strategies (name, strategy_type, timeframe, description, enabled, created_at, updated_at)
VALUES
('EMA50 Distance', 'EMA50_DISTANCE', 'daily', 'Buy >5% below 50 EMA. Mean reversion to EMA50. SL 4%, trail 0.3%. 134 trades/yr, 86.6% WR, PF 2.70.', true, NOW(), NOW()),
('RSI Oversold', 'RSI_OVERSOLD', 'daily', 'Buy when RSI(14)<35. Mean reversion to EMA50. SL 3%, trail 0.25%. 168 trades/yr, 86.9% WR, PF 3.02.', true, NOW(), NOW());
