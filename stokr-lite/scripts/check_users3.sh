#!/bin/bash
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, email, role, enabled, password IS NOT NULL as has_password FROM users ORDER BY id;"
