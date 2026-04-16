"""
WorldPulse BlueSky Scanner — monitors BlueSky social network for trending topics and sentiment.

MVP: Randomly generates a mock SocialTrendEvent within 2-10 seconds of startup.

FUTURE IDEAS:
────────────────────────────────────
1. Connect to BlueSky AT Protocol firehose using the atproto library
   - Subscribe to the com.atproto.sync.subscribeRepos stream
   - Filter for app.bsky.feed.post records
2. Run keyword matching against a configurable watchlist (env var KEYWORDS)
   - e.g., "AI regulation,climate summit,crypto crash,tech layoffs"
3. Aggregate post counts over a sliding time window (e.g., 5 minutes)
4. Basic sentiment analysis using TextBlob or VADER:
   - Classify each matching post as POSITIVE / NEGATIVE / NEUTRAL
   - Aggregate sentiment scores per topic
5. Trigger SocialTrendEvent when:
   - TRENDING: post count for a keyword exceeds threshold in time window
   - SENTIMENT_SPIKE: average sentiment shifts sharply (e.g., >0.3 delta)
   - KEYWORD_MATCH: specific high-priority keyword detected (immediate alert)
6. Rate-limit alerts to avoid flooding (max 1 alert per topic per 15 minutes)
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
logger = logging.getLogger("bluesky-scanner")

# Configuration via environment variables
KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SOURCE_NAME = "WorldPulse-BlueSkyScanner"

# FUTURE
# SCAN_INTERVAL = int(os.environ.get("SCAN_INTERVAL", "30"))  # seconds between aggregation cycles
# KEYWORDS = os.environ.get("KEYWORDS", "AI regulation,climate,crypto,tech layoffs").split(",")
# TRENDING_THRESHOLD = int(os.environ.get("TRENDING_THRESHOLD", "100"))  # posts per window
# SENTIMENT_DELTA_THRESHOLD = float(os.environ.get("SENTIMENT_DELTA_THRESHOLD", "0.3"))

# Mock data for V1 — simulates the kind of alerts the real scanner would produce
MOCK_TRENDS = [
    {
        "topic": "AI Regulation",
        "alertType": "TRENDING",
        "postCount": 1523,
        "sentiment": "NEGATIVE",
        "description": "Trending topic 'AI Regulation' detected with negative sentiment (1523 posts in last hour)",
    },
    {
        "topic": "Climate Summit 2026",
        "alertType": "SENTIMENT_SPIKE",
        "postCount": 842,
        "sentiment": "POSITIVE",
        "description": "Sentiment spike on 'Climate Summit 2026' — shifted from neutral to positive (842 posts)",
    },
    {
        "topic": "Crypto Crash",
        "alertType": "KEYWORD_MATCH",
        "postCount": 3891,
        "sentiment": "NEGATIVE",
        "description": "High-priority keyword 'Crypto Crash' detected with strong negative sentiment (3891 posts)",
    },
    {
        "topic": "Swiss Tech Week",
        "alertType": "TRENDING",
        "postCount": 467,
        "sentiment": "POSITIVE",
        "description": "Trending topic 'Swiss Tech Week' gaining traction with positive sentiment (467 posts)",
    },
]


def main():
    logger.info("WorldPulse BlueSky Scanner starting up...")
    logger.info(f"Kafka bootstrap servers: {KAFKA_BOOTSTRAP_SERVERS}")

    publisher = EventPublisher(KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    # V1: Wait a random 2-10 seconds, then publish one mock trend alert
    delay = random.uniform(2, 10)
    logger.info(f"V1 mode: will publish a mock SocialTrendEvent in {delay:.1f} seconds...")
    time.sleep(delay)

    # Pick a random mock trend
    trend_data = random.choice(MOCK_TRENDS)
    message = publisher.publish("SocialTrendEvent", trend_data)
    logger.info(f"Published SocialTrendEvent: {trend_data['description']}")
    logger.info(f"  Trace ID: {message['traceid']}")

    # FUTURE: Replace the block below with a continuous scanning loop:
    #
    # from atproto import FirehoseSubscribeReposClient, parse_subscribe_repos_message
    # from textblob import TextBlob
    # from collections import defaultdict
    #
    # ....

    # V1: Keep the container alive after publishing the mock event
    logger.info("V1 mock event published. Scanner idle (future: continuous scanning loop).")
    while True:
        time.sleep(60)


if __name__ == "__main__":
    main()
