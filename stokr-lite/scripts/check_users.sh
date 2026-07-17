#!/bin/bash
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "SELECT id, email, role, enabled FROM auth_users ORDER BY id;"
