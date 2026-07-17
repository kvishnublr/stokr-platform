#!/bin/bash
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%user%' OR table_name LIKE '%auth%' ORDER BY table_name;"
