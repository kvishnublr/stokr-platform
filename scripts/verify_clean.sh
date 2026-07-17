#!/bin/bash
echo "=== CHECKING FOR DELETED STRATEGY REFERENCES ==="
grep -rn 'VwapReversion\|VwapRejection\|VwapGrid\|VwapDip\|VwapBounce\|CashLiquidity\|OrbRetest\|GapVwap\|SmartMoney\|MomentumTrail\|GapReversal\|VolumeSpike\|IntradayHigh\|SectorOrb\|ThreeDayMomentum\|NiftyPulse\|EodMomentum\|ThreeDayExhaustion\|EmaPullback\|TrendContinuation\|TwentyDay\|EmaCross\|QuickFlip\|MomentumSurge\|BtstStrategy\|AfternoonBreakout\|InsiderMomentum\|CalendarSpread\|OrbBreakout\|PairsTrading\|MicroV\|InstitutionalFootprint\|DeadCat\|OvernightTrap\|VolumeCoil\|GapContinuation' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/ 2>/dev/null | grep -v '.class' || echo "NO DELETED STRATEGY REFERENCES FOUND IN JAVA"

echo ""
echo "=== CHECKING PLUGIN MAP ==="
grep 'Map.entry' /opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/engine/BacktestController.java

echo ""
echo "=== CHECKING FRONTEND ==="
grep -rn 'VWAP_REVERSION\|VWAP_REJECTION\|VWAP_GRID\|VWAP_DIP\|VWAP_BOUNCE\|CASH_IGNITION\|ORB_RETEST\|GAP_VWAP\|SMART_MONEY\|MOMENTUM_TRAIL\|GAP_REVERSAL\|VOLUME_SPIKE\|INTRADAY_HIGH\|SECTOR_ORB\|THREE_DAY_MOMENTUM\|NIFTY_PULSE\|EOD_MOMENTUM\|THREE_DAY_EXHAUSTION\|EMA_PULLBACK\|TREND_CONTINUATION\|TWENTY_DAY\|EMA_CROSS\|QUICK_FLIP\|MOMENTUM_SURGE\|BTST\|AFTERNOON_BREAKOUT\|INSIDER_MOMENTUM\|NIFTY_CALENDAR\|ORB_BREAKOUT\|PAIRS_TRADING\|MICRO_V\|INSTITUTIONAL_FOOTPRINT\|DEAD_CAT\|OVERNIGHT_TRAP\|VOLUME_COIL\|GAP_CONTINUATION\|3DM\|DCB\|3DE\|NPA\|VGS\|VDB\|VBL\|ORL\|CLI\|VRS\|SMF\|MT\|GVR\|IHB\|VBL2\|SORB\|EPS\|TCB\|20DB\|ECS\|VSM' /opt/stokr/stokr-platform/stokr-lite/frontend/src/ 2>/dev/null || echo "NO DELETED STRATEGY REFERENCES IN FRONTEND"
