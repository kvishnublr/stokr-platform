#!/bin/bash
PGPASSWORD=stokr2026 psql -h localhost -U postgres -d stokr_lite -c "\d users"
