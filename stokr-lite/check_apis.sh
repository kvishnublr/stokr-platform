#!/bin/bash
echo "=== /trades ==="
curl -sk https://stokr.in/api/option-arbitrage/trades 2>/dev/null | head -c 200
echo ""
echo "=== /daily-pnl ==="
curl -sk https://stokr.in/api/option-arbitrage/daily-pnl 2>/dev/null | head -c 200
echo ""
echo "=== /today ==="
curl -sk "https://stokr.in/api/option-arbitrage/today?underlying=ALL" 2>/dev/null | head -c 200
echo ""
echo "=== /live-pnl ==="
curl -sk https://stokr.in/api/option-arbitrage/history/live-pnl 2>/dev/null | head -c 200
echo ""
echo "=== /positions ==="
curl -sk https://stokr.in/api/option-arbitrage/positions 2>/dev/null | head -c 200
echo ""
echo "=== /bid-parity/positions ==="
curl -sk https://stokr.in/api/option-arbitrage/bid-parity/positions 2>/dev/null | head -c 200
echo ""
