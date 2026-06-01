"""
WorldPulse Bluesky Scanner — real-time social trend monitoring.

Primary source: Bluesky Jetstream (WebSocket)
Secondary source: Bluesky Search API backfill (HTTP polling)
"""

from __future__ import annotations

import asyncio
import itertools
import logging
import os
import time
from collections import OrderedDict

from aiohttp import web

from event_publisher import EventPublisher
from jetstream_client import JetstreamClient
from keyword_aggregator import KeywordAggregator
from search_poller import SearchPoller

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("bluesky-scanner")

KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SOURCE_NAME = "WorldPulse-BlueSkyScanner"

BLUESKY_MODE = os.environ.get("BLUESKY_MODE", "REAL").strip().upper()
SCAN_STRATEGY = os.environ.get("SCAN_STRATEGY", "REALTIME").strip().upper()
DEMO_MODE = os.environ.get("DEMO_MODE", "false").lower() == "true"
MOCK_EVENT_INTERVAL_SECONDS = max(0.1, float(os.environ.get("MOCK_EVENT_INTERVAL_SECONDS", "1")))
DEFAULT_KEYWORDS = "the,and,is" if DEMO_MODE else "AI regulation,climate summit,crypto crash,tech layoffs,swiss tech"
MIN_KEYWORD_LENGTH = max(2, int(os.environ.get("MIN_KEYWORD_LENGTH", "4")))
SEARCH_ENABLED = os.environ.get("SEARCH_ENABLED", "false").lower() == "true"
BLOCKED_SINGLE_KEYWORDS = {
    kw.strip().lower()
    for kw in os.environ.get("BLOCKED_SINGLE_KEYWORDS", "the,and,is,ai").split(",")
    if kw.strip()
}


def _sanitize_keywords(raw_keywords: list[str]) -> list[str]:
    if DEMO_MODE:
        return [kw for kw in raw_keywords if kw]

    filtered: list[str] = []
    for keyword in raw_keywords:
        kw = keyword.strip()
        if not kw:
            continue

        kw_lower = kw.lower()
        if " " not in kw and (kw_lower in BLOCKED_SINGLE_KEYWORDS or len(kw) < MIN_KEYWORD_LENGTH):
            logger.warning("Skipping overly generic keyword '%s'", kw)
            continue

        filtered.append(kw)

    if filtered:
        return filtered

    logger.warning("All keywords were filtered out; falling back to safe defaults.")
    return [
        "AI regulation",
        "climate summit",
        "crypto crash",
        "tech layoffs",
        "swiss tech",
    ]


KEYWORDS = _sanitize_keywords(
    [k.strip() for k in os.environ.get("KEYWORDS", DEFAULT_KEYWORDS).split(",") if k.strip()]
)
KEYWORDS_LOWER = [k.lower() for k in KEYWORDS]
WINDOW_SIZE = int(os.environ.get("WINDOW_SIZE", "60" if DEMO_MODE else "300"))
TRENDING_THRESHOLD = int(os.environ.get("TRENDING_THRESHOLD", "3" if DEMO_MODE else "50"))
EVAL_INTERVAL = int(os.environ.get("EVAL_INTERVAL", "10" if DEMO_MODE else "30"))
COOLDOWN_SECONDS = int(os.environ.get("COOLDOWN_SECONDS", "30" if DEMO_MODE else "900"))
SEARCH_INTERVAL = int(os.environ.get("SEARCH_INTERVAL", "120"))
SEARCH_LIMIT = int(os.environ.get("SEARCH_LIMIT", "25"))
ENGLISH_ONLY = os.environ.get("ENGLISH_ONLY", "false").lower() == "true"
SEEN_CID_CAPACITY = max(1, int(os.environ.get("SEEN_CID_CAPACITY", "10000")))
HEALTH_ENABLED = os.environ.get("HEALTH_ENABLED", "true").lower() == "true"
HEALTH_HOST = os.environ.get("HEALTH_HOST", "0.0.0.0")
HEALTH_PORT = int(os.environ.get("HEALTH_PORT", "8080"))

publisher: EventPublisher | None = None
aggregator: KeywordAggregator | None = None

last_alert_time: dict[str, float] = {}
# Single FIFO structure: O(1) membership check, insertion-ordered for FIFO
# eviction, no parallel data structures to keep in sync.
seen_cids: OrderedDict[str, None] = OrderedDict()
startup_time = time.time()

stats = {
    "posts_received": 0,
    "posts_matched": 0,
    "events_published": 0,
}


def is_english_post(langs: list[str]) -> bool:
    if not ENGLISH_ONLY:
        return True
    return any(isinstance(lang, str) and lang.lower().startswith("en") for lang in langs)


def register_seen_cid(cid: str | None) -> bool:
    """Returns True when CID is new (or absent), False if already seen.

    Bounded FIFO dedup: duplicates short-circuit before any reordering, so
    insertion order doubles as eviction order. Membership / insert / evict
    are all O(1). Using a single OrderedDict avoids the previous
    "queue + set" pair that could drift out of sync.
    """
    if not cid:
        return True

    if cid in seen_cids:
        return False

    seen_cids[cid] = None
    if len(seen_cids) > SEEN_CID_CAPACITY:
        seen_cids.popitem(last=False)
    return True


async def publish_realtime_match(keyword: str, post: dict) -> None:
    if publisher is None:
        return

    topic = keyword.title()
    text = post.get("text", "")
    payload = {
        "topic": topic,
        "alertType": "KEYWORD_MATCH",
        "postCount": 1,
        "sentiment": "NEUTRAL",
        "description": f"Realtime keyword match for '{topic}'",
        "source": post.get("source", "bluesky-jetstream"),
        "windowSeconds": 0,
        "samplePosts": [text[:280]] if text else [],
    }

    await asyncio.to_thread(publisher.publish, "SocialTrendEvent", payload)
    stats["events_published"] += 1
    logger.info("Published realtime SocialTrendEvent: %s", payload["description"])


async def handle_post(post: dict) -> None:
    if not register_seen_cid(post.get("cid")):
        return

    stats["posts_received"] += 1

    langs = post.get("langs") or []
    if not is_english_post(langs):
        return

    text = post.get("text", "")
    text_lower = text.lower()
    matched = [kw for kw in KEYWORDS_LOWER if kw in text_lower]

    if not matched:
        return

    stats["posts_matched"] += 1
    logger.debug("Matched keywords %s in post: %s", matched, text[:120])

    if SCAN_STRATEGY == "REALTIME":
        for keyword in matched:
            await publish_realtime_match(keyword, post)
        return

    if aggregator is not None:
        aggregator.register_post(text)


async def publish_alert(alert: dict) -> None:
    if publisher is None:
        return

    now = time.time()
    keyword = alert["keyword"]
    topic = alert["topic"]

    if now - last_alert_time.get(keyword, 0) < COOLDOWN_SECONDS:
        return

    payload = {
        "topic": topic,
        "alertType": alert["alertType"],
        "postCount": alert["postCount"],
        "sentiment": "NEUTRAL",
        "description": (
            f"Trending topic '{topic}' detected "
            f"({alert['postCount']} posts in {WINDOW_SIZE}s window)"
        ),
        "source": "bluesky-jetstream+search",
        "windowSeconds": alert["windowSeconds"],
        "samplePosts": alert["samplePosts"],
    }

    await asyncio.to_thread(publisher.publish, "SocialTrendEvent", payload)
    stats["events_published"] += 1
    last_alert_time[keyword] = now
    logger.info("Published SocialTrendEvent: %s", payload["description"])


async def evaluation_loop() -> None:
    if aggregator is None:
        return

    while True:
        await asyncio.sleep(EVAL_INTERVAL)
        alerts = aggregator.evaluate()
        for alert in alerts:
            await publish_alert(alert)


async def mocked_event_loop() -> None:
    """Publish exactly one mocked SocialTrendEvent per interval."""
    if publisher is None:
        return

    topics = KEYWORDS or ["Mock Topic"]
    counter = 0

    for keyword in itertools.cycle(topics):
        counter += 1
        topic = keyword.title()
        payload = {
            "topic": topic,
            "alertType": "TRENDING",
            "postCount": counter,
            "sentiment": "NEUTRAL",
            "description": f"MOCKED: '{topic}' simulated trend event #{counter}",
            "source": "bluesky-mocked",
            "windowSeconds": int(max(1, MOCK_EVENT_INTERVAL_SECONDS)),
            "samplePosts": [f"MOCKED post #{counter} for keyword '{keyword}'"],
        }
        await asyncio.to_thread(publisher.publish, "SocialTrendEvent", payload)
        stats["events_published"] += 1
        logger.info("Published mocked SocialTrendEvent: %s", payload["description"])
        await asyncio.sleep(MOCK_EVENT_INTERVAL_SECONDS)


async def healthz_handler(_: web.Request) -> web.Response:
    uptime = int(time.time() - startup_time)
    effective_mode = "MOCKED" if BLUESKY_MODE == "MOCKED" else "REAL"
    effective_strategy = "MOCKED" if BLUESKY_MODE == "MOCKED" else ("WINDOWED" if aggregator is not None else "REALTIME")

    return web.json_response(
        {
            "status": "ok",
            "service": "bluesky-scanner",
            "mode": effective_mode,
            "scanStrategy": effective_strategy,
            "uptimeSeconds": uptime,
            "keywords": KEYWORDS,
            "stats": stats,
        }
    )


async def run_health_server() -> None:
    if not HEALTH_ENABLED:
        logger.info("Health server disabled (HEALTH_ENABLED=false)")
        await asyncio.Event().wait()
        return

    app = web.Application()
    app.router.add_get("/healthz", healthz_handler)
    app.router.add_get("/", healthz_handler)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, HEALTH_HOST, HEALTH_PORT)
    await site.start()
    logger.info("Health endpoint listening on http://%s:%s/healthz", HEALTH_HOST, HEALTH_PORT)

    try:
        await asyncio.Event().wait()
    finally:
        await runner.cleanup()


async def main() -> None:
    global publisher, aggregator

    logger.info("WorldPulse BlueSky Scanner starting up...")
    logger.info("Kafka bootstrap servers: %s", KAFKA_BOOTSTRAP_SERVERS)
    logger.info("Keywords: %s", KEYWORDS)
    logger.info(
        "Mode=%s Strategy=%s Window=%ss Threshold=%s EvalInterval=%ss Cooldown=%ss DemoMode=%s",
        BLUESKY_MODE,
        SCAN_STRATEGY,
        WINDOW_SIZE,
        TRENDING_THRESHOLD,
        EVAL_INTERVAL,
        COOLDOWN_SECONDS,
        DEMO_MODE,
    )

    publisher = await asyncio.to_thread(EventPublisher, KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    try:
        if BLUESKY_MODE == "MOCKED":
            logger.info(
                "Running in MOCKED mode: publishing 1 event every %.1fs",
                MOCK_EVENT_INTERVAL_SECONDS,
            )
            await asyncio.gather(
                mocked_event_loop(),
                run_health_server(),
            )
            return

        if BLUESKY_MODE != "REAL":
            logger.warning("Unknown BLUESKY_MODE='%s'. Falling back to REAL mode.", BLUESKY_MODE)

        effective_scan_strategy = SCAN_STRATEGY
        if effective_scan_strategy not in {"REALTIME", "WINDOWED"}:
            logger.warning("Unknown SCAN_STRATEGY='%s'. Falling back to REALTIME.", SCAN_STRATEGY)
            effective_scan_strategy = "REALTIME"

        if effective_scan_strategy == "WINDOWED":
            aggregator = KeywordAggregator(KEYWORDS, WINDOW_SIZE, TRENDING_THRESHOLD)
            logger.info("Running WINDOWED strategy (backup mode).")
        else:
            logger.info("Running REALTIME strategy (default): emitting per matched post.")

        jetstream_client = JetstreamClient(handle_post)

        tasks = [
            jetstream_client.run(),
            run_health_server(),
        ]

        if SEARCH_ENABLED:
            logger.info("Search backfill enabled (interval=%ss, limit=%s)", SEARCH_INTERVAL, SEARCH_LIMIT)
            search_poller = SearchPoller(
                keywords=KEYWORDS,
                on_post_callback=handle_post,
                interval_seconds=SEARCH_INTERVAL,
                result_limit=SEARCH_LIMIT,
            )
            tasks.append(search_poller.run())
        else:
            logger.info("Search backfill disabled (SEARCH_ENABLED=false). Using Jetstream only.")

        if aggregator is not None:
            tasks.append(evaluation_loop())

        await asyncio.gather(*tasks)
    finally:
        # Flush buffered events and release the broker connection. Without
        # this, the Kafka producer's background thread can exit before
        # delivering in-flight batches.
        await asyncio.to_thread(publisher.close)


if __name__ == "__main__":
    asyncio.run(main())
