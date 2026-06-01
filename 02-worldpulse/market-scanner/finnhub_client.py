"""
Async Finnhub WebSocket client for real-time trade ingestion.
"""

from __future__ import annotations

import asyncio
import json
import logging
import random
from typing import Awaitable, Callable

import websockets

logger = logging.getLogger(__name__)

FINNHUB_WS_URL = "wss://ws.finnhub.io"

TradeCallback = Callable[[dict], Awaitable[None]]


class FinnhubClient:
    """Connects to Finnhub WebSocket, subscribes to symbols, and forwards trades."""

    def __init__(
        self,
        api_key: str,
        symbols: list[str],
        on_trade_callback: TradeCallback,
    ):
        self.api_key = api_key
        self.symbols = symbols
        self.on_trade = on_trade_callback
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    async def run(self) -> None:
        failure_count = 0
        url = f"{FINNHUB_WS_URL}?token={self.api_key}"

        while not self._stop:
            try:
                logger.info("Connecting to Finnhub WebSocket...")
                async with websockets.connect(
                    url,
                    ping_interval=20,
                    ping_timeout=20,
                    close_timeout=10,
                ) as ws:
                    logger.info("Connected to Finnhub WebSocket")
                    failure_count = 0

                    await self._subscribe(ws)

                    async for raw in ws:
                        if self._stop:
                            break

                        try:
                            msg = json.loads(raw)
                        except json.JSONDecodeError:
                            continue

                        if msg.get("type") == "ping":
                            continue

                        if msg.get("type") != "trade":
                            continue

                        for trade in msg.get("data", []):
                            await self.on_trade({
                                "symbol": trade["s"],
                                "price": trade["p"],
                                "volume": trade["v"],
                                "timestamp": trade["t"],
                            })

            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                failure_count += 1
                backoff = min(2**failure_count, 30)
                jitter = random.uniform(0, 0.5)
                wait_seconds = backoff + jitter
                logger.warning(
                    "Finnhub disconnected (%s). Reconnecting in %.1fs...",
                    exc,
                    wait_seconds,
                )
                await asyncio.sleep(wait_seconds)

    async def _subscribe(self, ws) -> None:
        for symbol in self.symbols:
            msg = json.dumps({"type": "subscribe", "symbol": symbol})
            await ws.send(msg)
            logger.info("Subscribed to %s", symbol)
