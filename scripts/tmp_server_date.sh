#!/bin/bash
date -u
date
docker exec stokr-api date
docker logs stokr-api 2>&1 | grep backfill | tail -15
