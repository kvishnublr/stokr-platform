#!/usr/bin/env python3
"""
ENTRY FILTER BACKTEST ANALYSIS
Execute on production server: 173.249.55.84
Analyzes all completed trades to identify best entry filters
"""

import psycopg2
import json
from collections import defaultdict
from datetime import datetime

def connect_db():
    return psycopg2.connect(
        host="173.249.55.84",
        port=5432,
        database="stokr_platform",
        user="stokr",
        password="stokr",
        connect_timeout=30
    )

def analyze_backtest():
    conn = connect_db()
    cursor = conn.cursor()

    print("\n" + "="*70)
    print("ENTRY FILTER BACKTEST ANALYSIS")
    print("="*70)

    # ===== SAMPLE SIZE CHECK =====
    print("\n[PHASE 1] SAMPLE SIZE VERIFICATION")
    print("-"*70)

    cursor.execute("""
        SELECT
          COUNT(*) as total_trades,
          COUNT(DISTINCT DATE(created_at)) as trading_days,
          MIN(DATE(created_at)) as first_date,
          MAX(DATE(created_at)) as last_date,
          COUNT(CASE WHEN realized_pnl > 0 THEN 1 END) as winners,
          COUNT(CASE WHEN realized_pnl < 0 THEN 1 END) as losers,
          COUNT(CASE WHEN realized_pnl = 0 THEN 1 END) as breakeven,
          ROUND(AVG(realized_pnl::numeric), 6) as avg_pnl,
          ROUND(SUM(realized_pnl::numeric), 6) as total_pnl,
          ROUND(COUNT(CASE WHEN realized_pnl > 0 THEN 1 END)::numeric / COUNT(*) * 100, 2) as win_rate
        FROM strategy_signals
        WHERE deleted = FALSE AND test_trade = FALSE AND backtest_run_id IS NULL
        AND outcome_status = 'CLOSED' AND realized_pnl IS NOT NULL;
    """)

    total, days, first_date, last_date, winners, losers, breakeven, avg_pnl, total_pnl, win_rate = cursor.fetchone()

    print(f"Total completed trades: {total}")
    print(f"Trading days: {days}")
    print(f"Date range: {first_date} to {last_date}")
    print(f"Winners/Losers/Breakeven: {winners}/{losers}/{breakeven}")
    print(f"Win rate: {win_rate}%")
    print(f"Average PnL: {avg_pnl}")
    print(f"Total PnL: {total_pnl}")

    if not total or total < 100:
        print(f"\n[INSUFFICIENT] Sample size {total} < 100")
        print("Need more trading data. Waiting for additional days...")
        conn.close()
        return

    print(f"\n[OK] Sample size {total} >= 100 - PROCEEDING\n")

    # ===== EXTRACT ALL TRADE DATA =====
    print("[PHASE 2] EXTRACTING TRADE DATA")
    print("-"*70)

    cursor.execute("""
        SELECT
          s.id,
          s.symbol,
          s.strategy_name,
          DATE(s.created_at) as trade_date,
          s.confidence_score,
          s.rsi_value,
          s.probability,
          s.vwap_distance,
          s.market_regime,
          s.trade_quality,
          s.entry_price,
          s.exit_price,
          s.realized_pnl,
          s.max_favorable_excursion,
          s.max_adverse_excursion,
          CASE WHEN s.realized_pnl > 0 THEN 1 ELSE 0 END as is_winner,
          COALESCE(e.exit_category, 'UNKNOWN') as exit_category,
          COALESCE(e.hold_seconds, 0) as hold_seconds
        FROM strategy_signals s
        LEFT JOIN strategy_exit_telemetry e ON s.id = e.signal_id
        WHERE s.deleted = FALSE AND s.test_trade = FALSE AND s.backtest_run_id IS NULL
        AND s.outcome_status = 'CLOSED' AND s.realized_pnl IS NOT NULL
        ORDER BY s.created_at DESC;
    """)

    trades = cursor.fetchall()
    print(f"Extracted {len(trades)} trades\n")

    # ===== CORRELATION ANALYSIS =====
    print("[PHASE 3] CORRELATION ANALYSIS: WINNERS vs LOSERS")
    print("-"*70)

    winners = []
    losers = []

    conf_scores = {'winner': [], 'loser': []}
    rsi_values = {'winner': [], 'loser': []}
    prob_values = {'winner': [], 'loser': []}
    vwap_values = {'winner': [], 'loser': []}
    market_regimes = defaultdict(lambda: {'winner': 0, 'loser': 0})
    trade_qualities = defaultdict(lambda: {'winner': 0, 'loser': 0})

    for trade in trades:
        (trade_id, symbol, strategy, date, conf, rsi, prob, vwap, regime,
         quality, entry, exit, pnl, mfe, mae, is_winner, exit_cat, hold) = trade

        if is_winner:
            winners.append(trade)
        else:
            losers.append(trade)

        category = 'winner' if is_winner else 'loser'

        if conf is not None:
            conf_scores[category].append(float(conf))
        if rsi is not None:
            rsi_values[category].append(float(rsi))
        if prob is not None:
            prob_values[category].append(float(prob))
        if vwap is not None:
            vwap_values[category].append(float(vwap))
        if regime:
            market_regimes[regime][category] += 1
        if quality:
            trade_qualities[quality][category] += 1

    print(f"Winners: {len(winners)}")
    print(f"Losers: {len(losers)}\n")

    # ===== METRIC ANALYSIS =====
    print("[PHASE 4] METRIC SEPARATION ANALYSIS")
    print("-"*70)

    results = []

    # Confidence Score
    if conf_scores['winner']:
        winner_avg = sum(conf_scores['winner']) / len(conf_scores['winner'])
        loser_avg = sum(conf_scores['loser']) / len(conf_scores['loser'])
        gap = winner_avg - loser_avg
        results.append(('Confidence Score', winner_avg, loser_avg, gap))
        print(f"\nCONFIDENCE SCORE:")
        print(f"  Winners avg: {winner_avg:.4f}")
        print(f"  Losers avg:  {loser_avg:.4f}")
        print(f"  Separation: {gap:.4f} {'[STRONG]' if abs(gap) > 0.10 else '[WEAK]'}")

    # RSI Value
    if rsi_values['winner']:
        winner_avg = sum(rsi_values['winner']) / len(rsi_values['winner'])
        loser_avg = sum(rsi_values['loser']) / len(rsi_values['loser'])
        gap = winner_avg - loser_avg
        results.append(('RSI Value', winner_avg, loser_avg, gap))
        print(f"\nRSI VALUE:")
        print(f"  Winners avg: {winner_avg:.2f}")
        print(f"  Losers avg:  {loser_avg:.2f}")
        print(f"  Separation: {gap:.2f} {'[STRONG]' if abs(gap) > 5 else '[WEAK]'}")

    # Probability
    if prob_values['winner']:
        winner_avg = sum(prob_values['winner']) / len(prob_values['winner'])
        loser_avg = sum(prob_values['loser']) / len(prob_values['loser'])
        gap = winner_avg - loser_avg
        results.append(('Probability', winner_avg, loser_avg, gap))
        print(f"\nPROBABILITY:")
        print(f"  Winners avg: {winner_avg:.4f}")
        print(f"  Losers avg:  {loser_avg:.4f}")
        print(f"  Separation: {gap:.4f} {'[STRONG]' if abs(gap) > 0.10 else '[WEAK]'}")

    # VWAP Distance
    if vwap_values['winner']:
        winner_avg = sum(vwap_values['winner']) / len(vwap_values['winner'])
        loser_avg = sum(vwap_values['loser']) / len(vwap_values['loser'])
        gap = winner_avg - loser_avg
        results.append(('VWAP Distance', winner_avg, loser_avg, gap))
        print(f"\nVWAP DISTANCE:")
        print(f"  Winners avg: {winner_avg:.6f}")
        print(f"  Losers avg:  {loser_avg:.6f}")
        print(f"  Separation: {gap:.6f} {'[STRONG]' if abs(gap) > 0.005 else '[WEAK]'}")

    # Market Regime
    print(f"\nMARKET REGIME:")
    for regime in sorted(market_regimes.keys()):
        w = market_regimes[regime]['winner']
        l = market_regimes[regime]['loser']
        total = w + l
        wr = (w / total * 100) if total > 0 else 0
        print(f"  {regime:12s}: {w:3d}/{total:3d} wins ({wr:5.1f}%)")

    # Trade Quality
    print(f"\nTRADE QUALITY:")
    for quality in sorted(trade_qualities.keys()):
        w = trade_qualities[quality]['winner']
        l = trade_qualities[quality]['loser']
        total = w + l
        wr = (w / total * 100) if total > 0 else 0
        print(f"  {quality:12s}: {w:3d}/{total:3d} wins ({wr:5.1f}%)")

    # ===== RANKING =====
    print(f"\n[PHASE 5] TOP 5 PREDICTORS (by separation)")
    print("-"*70)

    results_sorted = sorted(results, key=lambda x: abs(x[3]), reverse=True)
    for i, (metric, winner_avg, loser_avg, gap) in enumerate(results_sorted[:5], 1):
        print(f"{i}. {metric:20s} - Separation: {abs(gap):.6f}")

    # ===== RECOMMENDATION =====
    print(f"\n[PHASE 6] FILTER RECOMMENDATION")
    print("-"*70)

    if results_sorted:
        top_metric = results_sorted[0][0]
        print(f"\nStrongest predictor: {top_metric}")

        # Market Regime recommendation
        regime_scores = {}
        for regime, counts in market_regimes.items():
            wr = counts['winner'] / (counts['winner'] + counts['loser']) * 100
            regime_scores[regime] = wr

        best_regime = max(regime_scores.items(), key=lambda x: x[1])
        worst_regime = min(regime_scores.items(), key=lambda x: x[1])

        print(f"\nMarket Regime Analysis:")
        print(f"  Best: {best_regime[0]} ({best_regime[1]:.1f}% win rate)")
        print(f"  Worst: {worst_regime[0]} ({worst_regime[1]:.1f}% win rate)")

        if best_regime[1] - worst_regime[1] > 10:
            print(f"\n[RECOMMENDATION] Add Market Regime gate:")
            print(f"  Require {best_regime[0]} regime OR confidence > 80%")

    print("\n" + "="*70)
    print("ANALYSIS COMPLETE")
    print("="*70 + "\n")

    conn.close()

if __name__ == "__main__":
    analyze_backtest()
