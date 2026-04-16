"""
WorldPulse Market Scanner — monitors financial markets for significant price movements.

MVP: Randomly generates a mock MarketAlertEvent within 2-10 seconds of startup.

FUTURE IDEAS:
1. Poll Yahoo Finance API (yfinance) or CoinGecko API every SCAN_INTERVAL seconds
2. Track previous prices in memory to calculate change percentages
3. Detect threshold crossings:
   - PRICE_DROP: symbol drops >3% in the last hour
   - PRICE_SPIKE: symbol rises >5% in the last hour
   - VOLATILITY: standard deviation of 1h prices exceeds threshold
4. Support multiple symbols from WATCHLIST env var (e.g., "AAPL,GOOGL,BTC-USD")
"""

import logging
import os
import random
import time

from event_publisher import EventPublisher

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("market-scanner")

# Configuration via environment variables
KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SOURCE_NAME = "WorldPulse-MarketScanner"

# FUTURE IDEAS:
# SCAN_INTERVAL = int(os.environ.get("SCAN_INTERVAL", "60"))  # seconds between scans
# WATCHLIST = os.environ.get("WATCHLIST", "AAPL,GOOGL,MSFT,AMZN,BTC-USD").split(",")
# PRICE_DROP_THRESHOLD = float(os.environ.get("PRICE_DROP_THRESHOLD", "3.0"))  # percent
# PRICE_SPIKE_THRESHOLD = float(os.environ.get("PRICE_SPIKE_THRESHOLD", "5.0"))  # percent

# Mock data for V1 — simulates the kind of alerts the real scanner will produce
MOCK_ALERTS = [
    {
        "symbol": "AAPL",
        "alertType": "PRICE_DROP",
        "currentPrice": 178.52,
        "changePercent": -5.2,
        "description": "AAPL dropped 5.2% in the last hour",
    },
    {
        "symbol": "BTC-USD",
        "alertType": "PRICE_SPIKE",
        "currentPrice": 71843.00,
        "changePercent": 8.7,
        "description": "BTC-USD surged 8.7% in the last hour",
    },
    {
        "symbol": "GOOGL",
        "alertType": "VOLATILITY",
        "currentPrice": 174.20,
        "changePercent": -2.1,
        "description": "GOOGL showing unusual volatility (±4.3% swings in last hour)",
    },
    {
        "symbol": "NVDA",
        "alertType": "PRICE_DROP",
        "currentPrice": 89.14,
        "changePercent": -7.8,
        "description": "NVDA dropped 7.8% after earnings report",
    },
]


def main():
    logger.info("WorldPulse Market Scanner starting up...")
    logger.info(f"Kafka bootstrap servers: {KAFKA_BOOTSTRAP_SERVERS}")

    publisher = EventPublisher(KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    # V1: Wait a random 2-10 seconds, then publish one mock alert
    delay = random.uniform(2, 10)
    logger.info(f"V1 mode: will publish a mock MarketAlertEvent in {delay:.1f} seconds...")
    time.sleep(delay)

    # Pick a random mock alert
    alert_data = random.choice(MOCK_ALERTS)
    message = publisher.publish("MarketAlertEvent", alert_data)
    logger.info(f"Published MarketAlertEvent: {alert_data['description']}")
    logger.info(f"  Trace ID: {message['traceid']}")

    # FUTURE: Replace the block below with a continuous scanning loop:
    #
    # while True:
    #     for symbol in WATCHLIST:
    #         .....

    # V1: Keep the container alive after publishing the mock event
    logger.info("V1 mock event published. Scanner idle (future: continuous scanning loop).")
    while True:
        time.sleep(60)


if __name__ == "__main__":
    main()
