"""
WorldPulse Market Scanner — monitors financial markets for significant price movements.

Primary source: Finnhub WebSocket (real-time trades)
"""

from __future__ import annotations

import asyncio
import itertools
import logging
import os
import sys
import time

from aiohttp import web

from event_publisher import EventPublisher
from finnhub_client import FinnhubClient
from price_tracker import PriceTracker

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("market-scanner")

KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SOURCE_NAME = "WorldPulse-MarketScanner"

MARKET_MODE = os.environ.get("MARKET_MODE", "MOCKED").strip().upper()
FINNHUB_API_KEY = os.environ.get("FINNHUB_API_KEY", "").strip()
WATCHLIST = [s.strip().upper() for s in os.environ.get("WATCHLIST", "AAPL,MSFT,GOOGL,AMZN,TSLA").split(",") if s.strip()]
WINDOW_SIZE = int(os.environ.get("WINDOW_SIZE", "300"))
DROP_THRESHOLD = float(os.environ.get("DROP_THRESHOLD", "3.0"))
SPIKE_THRESHOLD = float(os.environ.get("SPIKE_THRESHOLD", "5.0"))
VOLATILITY_THRESHOLD = float(os.environ.get("VOLATILITY_THRESHOLD", "8.0"))
EVAL_INTERVAL = int(os.environ.get("EVAL_INTERVAL", "30"))
COOLDOWN_SECONDS = int(os.environ.get("COOLDOWN_SECONDS", "300"))
MOCK_EVENT_INTERVAL_SECONDS = max(0.1, float(os.environ.get("MOCK_EVENT_INTERVAL_SECONDS", "5")))
HEALTH_ENABLED = os.environ.get("HEALTH_ENABLED", "true").lower() == "true"
HEALTH_HOST = os.environ.get("HEALTH_HOST", "0.0.0.0")
HEALTH_PORT = int(os.environ.get("HEALTH_PORT", "8081"))

MOCK_ALERTS = [
    {"symbol": "TSLA", "alertType": "PRICE_DROP",  "baseChange": -5.2,  "currentPrice": 178.52},
    {"symbol": "JPM",  "alertType": "PRICE_SPIKE", "baseChange": 4.1,   "currentPrice": 214.30},
    {"symbol": "NFLX", "alertType": "VOLATILITY",  "baseChange": -3.4,  "currentPrice": 688.50},
    {"symbol": "META", "alertType": "PRICE_DROP",   "baseChange": -6.3,  "currentPrice": 512.70},
]

publisher: EventPublisher | None = None
tracker: PriceTracker | None = None

last_alert_time: dict[str, float] = {}
startup_time = time.time()

stats = {
    "trades_received": 0,
    "alerts_published": 0,
}


async def on_trade(trade: dict) -> None:
    if tracker is None:
        return
    stats["trades_received"] += 1
    tracker.record_trade(trade["symbol"], trade["price"])


async def evaluation_loop() -> None:
    if tracker is None or publisher is None:
        return

    while True:
        await asyncio.sleep(EVAL_INTERVAL)
        alerts = tracker.evaluate()

        now = time.time()
        for alert in alerts:
            symbol = alert["symbol"]
            if now - last_alert_time.get(symbol, 0) < COOLDOWN_SECONDS:
                continue

            await asyncio.to_thread(publisher.publish, "MarketAlertEvent", alert)
            stats["alerts_published"] += 1
            last_alert_time[symbol] = now
            logger.info("Published MarketAlertEvent: %s", alert["description"])


async def mocked_event_loop() -> None:
    if publisher is None:
        return

    rng = random.Random()
    counter = 0
    for alert_data in itertools.cycle(MOCK_ALERTS):
        counter += 1
        base = alert_data["baseChange"]
        jitter = rng.uniform(-1.5, 1.5)
        change = round(base + jitter, 2) if base < 0 else round(base + jitter, 2)
        symbol = alert_data["symbol"]
        direction = "dropped" if change < 0 else "surged"
        payload = {
            "symbol": symbol,
            "alertType": alert_data["alertType"],
            "currentPrice": alert_data["currentPrice"],
            "changePercent": change,
            "description": f"MOCKED #{counter}: {symbol} {direction} {abs(change):.1f}%",
        }
        await asyncio.to_thread(publisher.publish, "MarketAlertEvent", payload)
        stats["alerts_published"] += 1
        logger.info("Published mocked MarketAlertEvent: %s", payload["description"])
        await asyncio.sleep(MOCK_EVENT_INTERVAL_SECONDS)


async def healthz_handler(_: web.Request) -> web.Response:
    uptime = int(time.time() - startup_time)
    return web.json_response({
        "status": "ok",
        "service": "market-scanner",
        "mode": MARKET_MODE,
        "uptimeSeconds": uptime,
        "watchlist": WATCHLIST,
        "stats": stats,
    })


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
    global publisher, tracker

    logger.info("WorldPulse Market Scanner starting up...")
    logger.info("Kafka bootstrap servers: %s", KAFKA_BOOTSTRAP_SERVERS)
    logger.info("Mode=%s Watchlist=%s Window=%ss DropThreshold=%s%% SpikeThreshold=%s%% "
                "VolatilityThreshold=%s%% EvalInterval=%ss Cooldown=%ss",
                MARKET_MODE, WATCHLIST, WINDOW_SIZE, DROP_THRESHOLD, SPIKE_THRESHOLD,
                VOLATILITY_THRESHOLD, EVAL_INTERVAL, COOLDOWN_SECONDS)

    publisher = await asyncio.to_thread(EventPublisher, KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    try:
        if MARKET_MODE == "MOCKED":
            logger.info("Running in MOCKED mode: publishing 1 event every %.1fs", MOCK_EVENT_INTERVAL_SECONDS)
            await asyncio.gather(
                mocked_event_loop(),
                run_health_server(),
            )
            return

        if MARKET_MODE != "REAL":
            logger.warning("Unknown MARKET_MODE='%s'. Falling back to REAL mode.", MARKET_MODE)

        if not FINNHUB_API_KEY:
            logger.error("FINNHUB_API_KEY is required for REAL mode. Set it as an environment variable.")
            sys.exit(1)

        tracker = PriceTracker(
            symbols=WATCHLIST,
            window_size=WINDOW_SIZE,
            drop_threshold=DROP_THRESHOLD,
            spike_threshold=SPIKE_THRESHOLD,
            volatility_threshold=VOLATILITY_THRESHOLD,
        )

        finnhub_client = FinnhubClient(
            api_key=FINNHUB_API_KEY,
            symbols=WATCHLIST,
            on_trade_callback=on_trade,
        )

        logger.info("Starting Finnhub WebSocket client for symbols: %s", WATCHLIST)

        await asyncio.gather(
            finnhub_client.run(),
            evaluation_loop(),
            run_health_server(),
        )
    finally:
        # Flush buffered events and release the broker connection. Without
        # this, the Kafka producer's background thread can exit before
        # delivering in-flight batches.
        await asyncio.to_thread(publisher.close)


if __name__ == "__main__":
    asyncio.run(main())
