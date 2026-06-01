"""
Periodic Bluesky Search API poller for backfilling missed posts.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone
from typing import Awaitable, Callable

import aiohttp

logger = logging.getLogger(__name__)

SEARCH_URL = "https://public.api.bsky.app/xrpc/app.bsky.feed.searchPosts"
PostCallback = Callable[[dict], Awaitable[None]]


class SearchPoller:
    """Polls app.bsky.feed.searchPosts for configured keywords."""

    def __init__(
        self,
        keywords: list[str],
        on_post_callback: PostCallback,
        interval_seconds: int = 120,
        result_limit: int = 25,
    ):
        self.keywords = [kw.strip() for kw in keywords if kw.strip()]
        self.on_post = on_post_callback
        self.interval_seconds = interval_seconds
        self.result_limit = max(1, min(result_limit, 100))
        self._stop = False
        self._last_since: datetime = datetime.now(timezone.utc) - timedelta(minutes=10)

    def stop(self) -> None:
        self._stop = True

    async def run(self) -> None:
        timeout = aiohttp.ClientTimeout(total=20)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            while not self._stop:
                # Snapshot the cursor BEFORE polling. Anything published after
                # this instant is guaranteed to be picked up by the next
                # iteration; combined with the CID dedup in handle_post, that
                # means overlap is safe but no event is missed.
                start = datetime.now(timezone.utc)
                since_iso = self._last_since.isoformat().replace("+00:00", "Z")

                completed_full_cycle = True
                for keyword in self.keywords:
                    if self._stop:
                        completed_full_cycle = False
                        break
                    await self._poll_keyword(session, keyword, since_iso)

                # Only advance the cursor when we successfully polled every
                # keyword. On early shutdown we leave _last_since untouched so
                # a future restart re-polls the unprocessed window.
                if completed_full_cycle:
                    self._last_since = start

                if self._stop:
                    break
                await asyncio.sleep(self.interval_seconds)

    async def _poll_keyword(self, session: aiohttp.ClientSession, keyword: str, since_iso: str) -> None:
        params = {
            "q": keyword,
            "sort": "latest",
            "limit": self.result_limit,
            "since": since_iso,
        }

        try:
            async with session.get(SEARCH_URL, params=params) as response:
                if response.status != 200:
                    body = await response.text()
                    logger.warning(
                        "Search API returned %s for keyword '%s': %s",
                        response.status,
                        keyword,
                        body[:200],
                    )
                    return

                payload = await response.json()
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            logger.warning("Search API call failed for '%s': %s", keyword, exc)
            return

        for post in payload.get("posts", []):
            text = post.get("record", {}).get("text")
            if not isinstance(text, str) or not text.strip():
                continue

            await self.on_post(
                {
                    "text": text,
                    "cid": post.get("cid"),
                    "did": post.get("author", {}).get("did"),
                    "created_at": post.get("record", {}).get("createdAt"),
                    "langs": post.get("record", {}).get("langs") or [],
                    "source": "bluesky-search",
                }
            )
