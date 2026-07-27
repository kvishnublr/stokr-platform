#!/bin/bash
# Add timeframe column to strategies table
PGPASSWORD=`$POSTGRES_PASSWORD psql -h localhost -U postgres -d stokr_lite << 'EOF'
-- Add timeframe column
ALTER TABLE strategies ADD COLUMN IF NOT EXISTS timeframe VARCHAR(20) DEFAULT 'POSITIONAL';

-- Set correct values
-- ID 4: Morning Surge Reversal â†’ INTRA
-- ID 15: Oversold Bounce â†’ POSITIONAL
-- ID 16: Micro V-Reversal â†’ INTRA
-- ID 21: EMA50 Distance â†’ POSITIONAL
-- ID 23: 3 Red Days â†’ POSITIONAL
-- ID 18: Institutional Footprint â†’ POSITIONAL
-- ID 20: Institutional Footprint VSA â†’ POSITIONAL
UPDATE strategies SET timeframe = 'INTRA' WHERE id = 4;
UPDATE strategies SET timeframe = 'INTRA' WHERE id = 16;
UPDATE strategies SET timeframe = 'POSITIONAL' WHERE id IN (15, 21, 23, 18, 20);

-- Verify
SELECT id, name, timeframe FROM strategies ORDER BY id;
EOF

