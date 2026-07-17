#!/bin/bash
# Remove all losing/dead strategy Java files
# KEEP: MorningSurgeReversalStrategy, OversoldBounceStrategy, Ema50DistanceStrategy, ThreeRedDaysStrategy, RsiOversoldStrategy

STRAT_DIR="/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/strategy"

# REMOVE LIST - all losers and dead strategies
rm -v "$STRAT_DIR/MicroVReversalStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/InstitutionalFootprintStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VwapReversionStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VwapRejectionStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VwapGridScalperStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VwapDipBuyStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VwapBounceLongStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VwapBounceLongV2Strategy.java" 2>/dev/null
rm -v "$STRAT_DIR/CashLiquidityIgnitionStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/OrbRetestLongStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/GapVwapRetestStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/SmartMoneyFlowStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/MomentumTrailStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/GapReversalStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/VolumeSpikeMomentumStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/IntradayHighBreakoutStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/SectorAnchoredORBStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/ThreeDayMomentumSwingStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/NiftyPulseStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/NiftyPulseV2Strategy.java" 2>/dev/null
rm -v "$STRAT_DIR/EodMomentumStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/ThreeDayExhaustionStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/EmaPullbackSwingStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/TrendContinuationBreakoutStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/TwentyDayBreakoutStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/EmaCrossSwingStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/QuickFlipStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/MomentumSurgeStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/BtstStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/AfternoonBreakoutStrategy.java" 2>/dev/null
rm -v "$STRAT_DIR/GapsReversalStrategy.java" 2>/dev/null

echo "---"
echo "REMAINING strategy files:"
ls -1 "$STRAT_DIR"/*.java | grep -v 'Strategy.java$' | grep -v 'StrategyPlugin.java' | grep -v 'Signal.java' | grep -v 'MarketContext.java' | grep -v 'StrategyParams.java'
echo "---"
echo "Kept strategies:"
ls "$STRAT_DIR"/*Strategy.java | xargs -I{} basename {}
