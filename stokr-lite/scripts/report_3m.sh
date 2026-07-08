#!/bin/bash
API="http://localhost:8081/api/backtest/advanced"
START="2026-04-07"
END="2026-07-07"

echo "============================================================"
echo "  3-MONTH BACKTEST REPORT (Apr 7 - Jul 7, 2026)"
echo "  Capital: ₹1,00,000 | Universe: NIFTY_50"
echo "============================================================"
echo ""

echo "=== EMA50_DISTANCE ==="
curl -s -X POST "${API}?strategy=EMA50_DISTANCE&universe=NIFTY_50&dateStart=${START}&dateEnd=${END}&capital=100000" > /tmp/ema50_3m.json
python3 /tmp/report.py /tmp/ema50_3m.json
echo ""

echo "=== RSI_OVERSOLD ==="
curl -s -X POST "${API}?strategy=RSI_OVERSOLD&universe=NIFTY_50&dateStart=${START}&dateEnd=${END}&capital=100000" > /tmp/rsio_3m.json
python3 /tmp/report.py /tmp/rsio_3m.json
echo ""

echo "=== OVERSOLD_BOUNCE (baseline comparison) ==="
curl -s -X POST "${API}?strategy=OVERSOLD_BOUNCE&universe=NIFTY_50&dateStart=${START}&dateEnd=${END}&capital=100000" > /tmp/ob_3m.json
python3 /tmp/report.py /tmp/ob_3m.json
