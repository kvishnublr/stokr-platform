#!/usr/bin/env python3
"""
HYBRID EXIT ENGINE - Layer 1: Strategy + Layer 2: Indicators + Layer 3: AI Optimization
Real-time technical analysis and dynamic exit calculation for STOKR platform
"""

import json
import math
from datetime import datetime
from typing import Dict, List, Tuple, Optional

class TechnicalIndicators:
    """Calculate real-time technical indicators"""

    @staticmethod
    def calculate_rsi(prices: List[float], period: int = 14) -> float:
        """Calculate RSI (Relative Strength Index)"""
        if len(prices) < period + 1:
            return 50.0

        deltas = [prices[i] - prices[i-1] for i in range(1, len(prices))]
        gains = [d if d > 0 else 0 for d in deltas[-period:]]
        losses = [abs(d) if d < 0 else 0 for d in deltas[-period:]]

        avg_gain = sum(gains) / period
        avg_loss = sum(losses) / period

        if avg_loss == 0:
            return 100.0 if avg_gain > 0 else 50.0

        rs = avg_gain / avg_loss
        rsi = 100 - (100 / (1 + rs))
        return rsi

    @staticmethod
    def calculate_macd(prices: List[float]) -> Tuple[float, float, float]:
        """Calculate MACD (Moving Average Convergence Divergence)"""
        if len(prices) < 26:
            return 0.0, 0.0, 0.0

        ema12 = TechnicalIndicators.calculate_ema(prices, 12)
        ema26 = TechnicalIndicators.calculate_ema(prices, 26)
        macd_line = ema12 - ema26

        macd_history = []
        for i in range(len(prices) - 26 + 1):
            e12 = TechnicalIndicators.calculate_ema(prices[:i + 26], 12)
            e26 = TechnicalIndicators.calculate_ema(prices[:i + 26], 26)
            macd_history.append(e12 - e26)

        signal_line = TechnicalIndicators.calculate_ema(macd_history[-9:], 9) if len(macd_history) >= 9 else 0.0
        histogram = macd_line - signal_line

        return macd_line, signal_line, histogram

    @staticmethod
    def calculate_ema(prices: List[float], period: int) -> float:
        """Calculate EMA (Exponential Moving Average)"""
        if len(prices) < period:
            return sum(prices) / len(prices)

        k = 2 / (period + 1)
        ema = sum(prices[-period:]) / period

        for price in prices[-period:]:
            ema = price * k + ema * (1 - k)

        return ema

    @staticmethod
    def calculate_atr(high: List[float], low: List[float], close: List[float], period: int = 14) -> float:
        """Calculate ATR (Average True Range)"""
        if len(high) < period:
            return 0.0

        tr_values = []
        for i in range(len(high)):
            tr = max(
                high[i] - low[i],
                abs(high[i] - close[i-1]) if i > 0 else high[i] - low[i],
                abs(low[i] - close[i-1]) if i > 0 else high[i] - low[i]
            )
            tr_values.append(tr)

        atr = sum(tr_values[-period:]) / period
        return atr

    @staticmethod
    def calculate_bollinger_bands(prices: List[float], period: int = 20) -> Tuple[float, float, float]:
        """Calculate Bollinger Bands"""
        if len(prices) < period:
            return prices[-1], prices[-1], prices[-1]

        sma = sum(prices[-period:]) / period
        variance = sum((p - sma) ** 2 for p in prices[-period:]) / period
        std_dev = math.sqrt(variance)

        upper = sma + (std_dev * 2)
        lower = sma - (std_dev * 2)

        return upper, sma, lower

    @staticmethod
    def calculate_volume_rsi(volumes: List[float], period: int = 14) -> float:
        """Calculate Volume-based RSI"""
        if len(volumes) < period:
            return 50.0

        recent_avg = sum(volumes[-period:]) / period
        current_volume = volumes[-1]

        if current_volume > recent_avg * 1.5:
            return 75.0
        elif current_volume < recent_avg * 0.7:
            return 25.0
        else:
            return 50.0


class ExitSignalGenerator:
    """Generate exit signals based on technical indicators"""

    @staticmethod
    def generate_indicator_signals(
        symbol: str,
        qty: int,
        entry_price: float,
        current_price: float,
        prices: List[float],
        volumes: List[float],
        high_prices: List[float] = None,
        low_prices: List[float] = None
    ) -> Dict:
        """Generate exit signals based on indicators"""

        signals = {
            'symbol': symbol,
            'timestamp': datetime.now().isoformat(),
            'signals': [],
            'confidence': 0.0,
            'recommendation': 'HOLD'
        }

        # Calculate indicators
        rsi = TechnicalIndicators.calculate_rsi(prices)
        macd_line, signal_line, histogram = TechnicalIndicators.calculate_macd(prices)
        vol_rsi = TechnicalIndicators.calculate_volume_rsi(volumes)

        atr = 0.0
        if high_prices and low_prices:
            atr = TechnicalIndicators.calculate_atr(high_prices, low_prices, prices)

        upper_bb, middle_bb, lower_bb = TechnicalIndicators.calculate_bollinger_bands(prices)

        # Store indicator values
        signals['indicators'] = {
            'rsi': rsi,
            'macd_line': macd_line,
            'macd_signal': signal_line,
            'macd_histogram': histogram,
            'volume_rsi': vol_rsi,
            'atr': atr,
            'bb_upper': upper_bb,
            'bb_middle': middle_bb,
            'bb_lower': lower_bb
        }

        # Generate signals
        is_long = qty > 0
        is_short = qty < 0
        profit_percent = ((current_price - entry_price) / entry_price) * 100

        # RSI signals
        if is_long and rsi > 70:
            signals['signals'].append({
                'type': 'RSI_OVERBOUGHT',
                'strength': (rsi - 70) / 30,
                'action': 'EXIT_LONG'
            })

        if is_short and rsi < 30:
            signals['signals'].append({
                'type': 'RSI_OVERSOLD',
                'strength': (30 - rsi) / 30,
                'action': 'EXIT_SHORT'
            })

        # MACD signals
        if is_long and histogram < 0 and macd_line < signal_line:
            signals['signals'].append({
                'type': 'MACD_BEARISH_CROSSOVER',
                'strength': abs(histogram) / abs(macd_line) if macd_line != 0 else 0.5,
                'action': 'EXIT_LONG'
            })

        if is_short and histogram > 0 and macd_line > signal_line:
            signals['signals'].append({
                'type': 'MACD_BULLISH_CROSSOVER',
                'strength': abs(histogram) / abs(macd_line) if macd_line != 0 else 0.5,
                'action': 'EXIT_SHORT'
            })

        # Bollinger Band signals
        if is_long and current_price >= upper_bb:
            signals['signals'].append({
                'type': 'BB_UPPER_TOUCH',
                'strength': min(1.0, (current_price - upper_bb) / (upper_bb * 0.05)),
                'action': 'TAKE_PROFIT'
            })

        if is_short and current_price <= lower_bb:
            signals['signals'].append({
                'type': 'BB_LOWER_TOUCH',
                'strength': min(1.0, (lower_bb - current_price) / (lower_bb * 0.05)),
                'action': 'TAKE_PROFIT'
            })

        # Volume signals
        if is_long and vol_rsi > 70 and rsi > 75:
            signals['signals'].append({
                'type': 'HIGH_VOLUME_EXIT',
                'strength': 0.7,
                'action': 'TAKE_PROFIT'
            })

        # Calculate overall confidence
        if signals['signals']:
            strengths = [s.get('strength', 0.5) for s in signals['signals']]
            signals['confidence'] = min(1.0, sum(strengths) / max(len(strengths), 1))

            # Determine recommendation
            exit_count = sum(1 for s in signals['signals'] if 'EXIT' in s.get('action', ''))
            if signals['confidence'] > 0.75 and exit_count > 1:
                signals['recommendation'] = 'STRONG_EXIT'
            elif signals['confidence'] > 0.6 and exit_count > 0:
                signals['recommendation'] = 'EXIT'
            elif signals['confidence'] > 0.5:
                signals['recommendation'] = 'WEAK_EXIT'

        return signals


class DynamicTargetCalculator:
    """Calculate dynamic exit targets based on indicators"""

    @staticmethod
    def calculate_dynamic_target(
        symbol: str,
        entry_price: float,
        current_price: float,
        qty: int,
        atr: float,
        rsi: float,
        macd_histogram: float,
        volume_strength: float = 0.5,
        strategy_target: float = None
    ) -> Dict:
        """Calculate optimal dynamic target"""

        is_long = qty > 0
        profit_percent = ((current_price - entry_price) / entry_price) * 100

        # Base target from strategy
        if strategy_target:
            base_target = strategy_target
        else:
            base_target = entry_price * (1.02 if is_long else 0.98)

        # Volatility adjustment (wider target in high volatility)
        volatility_factor = 1 + (atr / entry_price) * 0.5

        # Momentum adjustment (more aggressive in strong momentum)
        momentum_factor = 1 + (macd_histogram / entry_price) * 0.1 if macd_histogram != 0 else 1.0

        # RSI adjustment (take profits earlier in overbought/oversold)
        rsi_factor = 1.0
        if is_long and rsi > 70:
            rsi_factor = 1.0 - ((rsi - 70) / 30) * 0.2
        elif not is_long and rsi < 30:
            rsi_factor = 1.0 - ((30 - rsi) / 30) * 0.2

        # Calculate adjusted target
        if is_long:
            dynamic_target = base_target * volatility_factor * momentum_factor * rsi_factor
        else:
            dynamic_target = base_target / (volatility_factor * momentum_factor * rsi_factor)

        # Calculate dynamic stop loss
        dynamic_stop = entry_price * (1.0 - atr / entry_price) if is_long else entry_price * (1.0 + atr / entry_price)

        return {
            'symbol': symbol,
            'original_target': base_target,
            'dynamic_target': dynamic_target,
            'dynamic_stop': dynamic_stop,
            'volatility_factor': volatility_factor,
            'momentum_factor': momentum_factor,
            'rsi_factor': rsi_factor,
            'profit_potential': abs((dynamic_target - current_price) / current_price * 100),
            'risk_level': abs((current_price - dynamic_stop) / current_price * 100)
        }


class HybridExitEngine:
    """Complete hybrid exit system combining strategy + indicators + AI"""

    def __init__(self):
        self.signals_generator = ExitSignalGenerator()
        self.target_calculator = DynamicTargetCalculator()

    def process_position(
        self,
        position: Dict,
        price_history: List[float],
        volume_history: List[float],
        strategy_exit_signal: Optional[str] = None
    ) -> Dict:
        """Process position and generate exit decision"""

        symbol = position['symbol']
        qty = position['qty']
        entry_price = position['entry_price']
        current_price = position['current_price']

        # Layer 1: Strategy Exit
        strategy_decision = 'NONE'
        strategy_confidence = 0.0
        if strategy_exit_signal:
            strategy_decision = strategy_exit_signal
            strategy_confidence = 0.8

        # Layer 2: Indicator-Based Exit
        indicator_signals = self.signals_generator.generate_indicator_signals(
            symbol, qty, entry_price, current_price,
            price_history, volume_history
        )

        # Layer 3: Dynamic Target Calculation
        atr = TechnicalIndicators.calculate_atr(
            [current_price] * len(price_history),  # Simplified for demo
            [current_price * 0.99] * len(price_history),
            price_history
        ) if len(price_history) > 14 else current_price * 0.02

        rsi = indicator_signals['indicators']['rsi']
        macd_histogram = indicator_signals['indicators']['macd_histogram']

        dynamic_target = self.target_calculator.calculate_dynamic_target(
            symbol, entry_price, current_price, qty,
            atr, rsi, macd_histogram
        )

        # Combined decision
        combined_confidence = (strategy_confidence + indicator_signals['confidence']) / 2

        final_decision = 'HOLD'
        if strategy_decision != 'NONE':
            final_decision = strategy_decision
        elif indicator_signals['recommendation'] in ['STRONG_EXIT', 'EXIT']:
            final_decision = indicator_signals['recommendation']

        return {
            'symbol': symbol,
            'timestamp': datetime.now().isoformat(),
            'strategy_decision': strategy_decision,
            'strategy_confidence': strategy_confidence,
            'indicator_signals': indicator_signals,
            'dynamic_target': dynamic_target,
            'final_decision': final_decision,
            'confidence': combined_confidence,
            'actions': {
                'exit_target': dynamic_target['dynamic_target'],
                'stop_loss': dynamic_target['dynamic_stop'],
                'position_size': abs(qty),
                'entry_price': entry_price,
                'current_price': current_price,
                'current_pnl': (current_price - entry_price) * abs(qty)
            }
        }


if __name__ == "__main__":
    # Example usage
    engine = HybridExitEngine()

    example_position = {
        'symbol': 'HDFCBANK',
        'qty': -1,
        'entry_price': 741.55,
        'current_price': 735.70
    }

    # Mock price and volume history
    price_history = [740 + i*0.1 for i in range(50)]
    volume_history = [1000000 + i*10000 for i in range(50)]

    result = engine.process_position(example_position, price_history, volume_history)
    print(json.dumps(result, indent=2))
