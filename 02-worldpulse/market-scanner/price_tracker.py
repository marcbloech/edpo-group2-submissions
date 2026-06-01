"""
Sliding-window price tracking with threshold-based alert detection.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque


class PriceTracker:
    """Tracks recent prices per symbol and detects significant movements."""

    def __init__(
        self,
        symbols: list[str],
        window_size: int = 300,
        drop_threshold: float = 3.0,
        spike_threshold: float = 5.0,
        volatility_threshold: float = 8.0,
    ):
        self.symbols = [s.strip().upper() for s in symbols if s.strip()]
        self.window_size = window_size
        self.drop_threshold = drop_threshold
        self.spike_threshold = spike_threshold
        self.volatility_threshold = volatility_threshold

        self._prices: dict[str, deque[tuple[float, float]]] = defaultdict(deque)
        self._latest_price: dict[str, float] = {}

    def record_trade(self, symbol: str, price: float, timestamp: float | None = None) -> None:
        ts = timestamp if timestamp is not None else time.time()
        self._prices[symbol].append((ts, price))
        self._latest_price[symbol] = price

    def evaluate(self, now: float | None = None) -> list[dict]:
        current = now if now is not None else time.time()
        cutoff = current - self.window_size

        alerts: list[dict] = []
        for symbol in self.symbols:
            prices = self._prices[symbol]

            while prices and prices[0][0] < cutoff:
                prices.popleft()

            if len(prices) < 2:
                continue

            oldest_price = prices[0][1]
            newest_price = prices[-1][1]

            # Guard against non-positive prices. Real equity / FX feeds should
            # never emit these, but a bad tick (0.0 from a serialisation glitch
            # or a halted symbol) would otherwise divide by zero or yield a
            # negative-denominator percentage.
            if oldest_price <= 0:
                continue

            change_pct = ((newest_price - oldest_price) / oldest_price) * 100

            alert_type = None
            if change_pct <= -self.drop_threshold:
                alert_type = "PRICE_DROP"
            elif change_pct >= self.spike_threshold:
                alert_type = "PRICE_SPIKE"
            else:
                all_prices = [p for _, p in prices]
                min_p, max_p = min(all_prices), max(all_prices)
                if min_p <= 0:
                    continue
                range_pct = ((max_p - min_p) / min_p) * 100
                if range_pct >= self.volatility_threshold:
                    alert_type = "VOLATILITY"

            if alert_type is not None:
                direction = "dropped" if change_pct < 0 else "rose"
                alerts.append({
                    "symbol": symbol,
                    "alertType": alert_type,
                    "currentPrice": newest_price,
                    "changePercent": round(change_pct, 2),
                    "description": f"{symbol} {direction} {abs(change_pct):.1f}% in the last {self.window_size}s",
                })

        return alerts
