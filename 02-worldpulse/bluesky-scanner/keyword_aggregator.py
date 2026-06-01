"""
Sliding-window keyword aggregation for social trend detection.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque


class KeywordAggregator:
    def __init__(
        self,
        keywords: list[str],
        window_size: int = 300,
        trending_threshold: int = 50,
        max_samples: int = 3,
    ):
        self.keywords = [kw.strip().lower() for kw in keywords if kw.strip()]
        self.window_size = window_size
        self.trending_threshold = trending_threshold
        self.max_samples = max_samples

        self._timestamps: dict[str, deque[float]] = defaultdict(deque)
        self._samples: dict[str, deque[str]] = defaultdict(
            lambda: deque(maxlen=self.max_samples)
        )

    def register_post(self, text: str, timestamp: float | None = None) -> list[str]:
        """Register a post and return matched keywords."""
        ts = timestamp if timestamp is not None else time.time()
        text_lower = text.lower()
        matched: list[str] = []

        for keyword in self.keywords:
            if keyword in text_lower:
                self._timestamps[keyword].append(ts)

                sample_queue = self._samples[keyword]
                sample_queue.append(text[:280])

                matched.append(keyword)

        return matched

    def evaluate(self, now: float | None = None) -> list[dict]:
        """Evaluate keyword windows and return alert payloads for trending topics."""
        current = now if now is not None else time.time()
        cutoff = current - self.window_size

        alerts: list[dict] = []
        for keyword in self.keywords:
            timestamps = self._timestamps[keyword]
            while timestamps and timestamps[0] < cutoff:
                timestamps.popleft()

            count = len(timestamps)
            if count >= self.trending_threshold:
                alerts.append(
                    {
                        "keyword": keyword,
                        "topic": keyword.title(),
                        "alertType": "TRENDING",
                        "postCount": count,
                        "windowSeconds": self.window_size,
                        "samplePosts": list(self._samples[keyword]),
                    }
                )

        return alerts
