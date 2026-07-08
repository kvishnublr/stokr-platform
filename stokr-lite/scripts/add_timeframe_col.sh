#!/bin/bash
# Add timeframe column to strategies table
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite << 'EOF'
-- Add timeframe column
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS timeframe VARCHAR(20) DEFAULT 'POSITIONAL';

-- Set correct values
-- ID 4: Morning Surge Reversal → INTRA
-- ID 15: Oversold Bounce → POSITIONAL
-- ID 16: Micro V-Reversal → INTRA
-- ID 21: EMA50 Distance → POSITIONAL
-- ID 23: 3 Red Days → POSITIONAL
-- ID 18: Institutional Footprint → POSITIONAL
-- ID 20: Institutional Footprint VSA → POSITIONAL
UPDATE strategies SET timeframe = 'INTRA' WHERE id = 4;
UPDATE strategies SET timeframe = 'INTRA' WHERE id = 16;
UPDATE strategies SET timeframe = 'POSITIONAL' WHERE id IN (15, 21, 23, 18, 20);

-- Verify
SELECT id, name, timeframe FROM strategies ORDER BY id;
EOF
