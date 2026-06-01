"""
Async Jetstream WebSocket client for Bluesky post ingestion.
"""

from __future__ import annotations

import asyncio
import json
import logging
import random
from typing import Awaitable, Callable

import websockets

logger = logging.getLogger(__name__)

JETSTREAM_URLS = [
    "wss://jetstream1.us-east.bsky.network/subscribe",
    "wss://jetstream2.us-east.bsky.network/subscribe",
    "wss://jetstream1.us-west.bsky.network/subscribe",
    "wss://jetstream2.us-west.bsky.network/subscribe",
]

PostCallback = Callable[[dict], Awaitable[None]]


class JetstreamClient:
    """Consumes Bluesky Jetstream events and forwards post creates to a callback."""

    def __init__(
        self,
        on_post_callback: PostCallback,
        wanted_collection: str = "app.bsky.feed.post",
        start_url_index: int = 0,
    ):
        self.on_post = on_post_callback
        self.wanted_collection = wanted_collection
        self.url_index = start_url_index
        self.last_cursor: int | None = None  # Unix microseconds
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    async def run(self) -> None:
        failure_count = 0

        while not self._stop:
            base_url = JETSTREAM_URLS[self.url_index % len(JETSTREAM_URLS)]
            full_url = self._build_url(base_url)

            try:
                logger.info("Connecting to Jetstream: %s", full_url)
                async with websockets.connect(
                    full_url,
                    ping_interval=20,
                    ping_timeout=20,
                    close_timeout=10,
                    max_size=10 * 1024 * 1024,
                ) as ws:
                    logger.info("Connected to Jetstream host: %s", base_url)
                    failure_count = 0

                    async for raw in ws:
                        if self._stop:
                            break

                        try:
                            event = json.loads(raw)
                        except json.JSONDecodeError:
                            logger.debug("Ignoring non-JSON Jetstream message")
                            continue

                        time_us = event.get("time_us")
                        if isinstance(time_us, int):
                            self.last_cursor = time_us

                        post = self._extract_post(event)
                        if post is None:
                            continue

                        await self.on_post(post)

            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                failure_count += 1
                self.url_index += 1
                backoff = min(2**failure_count, 30)
                jitter = random.uniform(0, 0.5)
                wait_seconds = backoff + jitter
                logger.warning(
                    "Jetstream disconnected (%s). Reconnecting in %.1fs...",
                    exc,
                    wait_seconds,
                )
                await asyncio.sleep(wait_seconds)

    def _build_url(self, base_url: str) -> str:
        query = [f"wantedCollections={self.wanted_collection}"]
        if self.last_cursor is not None:
            query.append(f"cursor={self.last_cursor}")
        return f"{base_url}?{'&'.join(query)}"

    def _extract_post(self, event: dict) -> dict | None:
        if event.get("kind") != "commit":
            return None

        commit = event.get("commit", {})
        if commit.get("operation") != "create":
            return None

        if commit.get("collection") != self.wanted_collection:
            return None

        record = commit.get("record", {})
        text = record.get("text")
        if not isinstance(text, str) or not text.strip():
            return None

        return {
            "text": text,
            "cid": commit.get("cid"),
            "did": event.get("did"),
            "time_us": event.get("time_us"),
            "created_at": record.get("createdAt"),
            "langs": record.get("langs") or [],
            "rkey": commit.get("rkey"),
        }
