#!/bin/bash
curl -s -X POST http://localhost:8070/webhooks/chartink/intraday \
  -H 'Content-Type: application/json' \
  -d '{
    "scannerName": "ORB_BREAKOUT",
    "scanName": "ORB Breakout Test",
    "symbol": "RELIANCE",
    "ltp": 2500.00,
    "volume": 100000,
    "buyerQty": 150000,
    "sellerQty": 80000,
    "changePct": 1.2,
    "rvol": 1.8,
    "atr14": 15.0,
    "adx14": 30.0
  }'
echo ""
