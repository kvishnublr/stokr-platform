#!/bin/bash
PGPASSWORD=root123 psql -h 127.0.0.1 -U stokr -d stokr_lite -t -A -F'|' -c "SELECT id, underlying, strike, action, legs, description FROM option_arb_opportunities WHERE id = 261112;"
