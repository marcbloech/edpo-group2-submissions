import argparse
import logging
import os
import random
import signal
import sys
import time
from datetime import datetime, timezone

from event_publisher import EventPublisher

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("login-generator")

KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SOURCE_NAME = "WorldPulse-LoginGenerator"

LOCATIONS = ["Zurich", "Geneva", "New York", "London", "Tokyo", "Singapore"]
DEVICE_TYPES = ["web", "mobile", "api"]
USER_IDS = [f"user-{i}" for i in range(1, 21)]

running = True


def handle_shutdown(signum, frame):
    global running
    logger.info("Shutdown signal received, finishing...")
    running = False


def generate_login_event():
    return {
        "userId": random.choice(USER_IDS),
        "location": random.choice(LOCATIONS),
        "deviceType": random.choice(DEVICE_TYPES),
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def main():
    parser = argparse.ArgumentParser(description="WorldPulse Login Event Generator")
    parser.add_argument("--count", type=int,
                        default=int(os.environ.get("LOGIN_COUNT", "50")))
    parser.add_argument("--interval-ms", type=int,
                        default=int(os.environ.get("LOGIN_INTERVAL_MS", "200")))
    parser.add_argument("--continuous", action="store_true",
                        default=os.environ.get("LOGIN_CONTINUOUS", "false").lower() == "true")
    args = parser.parse_args()

    interval_sec = args.interval_ms / 1000.0

    signal.signal(signal.SIGINT, handle_shutdown)
    signal.signal(signal.SIGTERM, handle_shutdown)

    logger.info("Login Generator starting — bootstrap=%s count=%s interval=%dms continuous=%s",
                KAFKA_BOOTSTRAP_SERVERS, args.count, args.interval_ms, args.continuous)

    publisher = EventPublisher(KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    published = 0
    try:
        while running:
            event_data = generate_login_event()
            publisher.publish("LoginEvent", event_data)
            published += 1

            if not args.continuous and published >= args.count:
                logger.info("Burst complete: %d LoginEvents published", published)
                break

            time.sleep(interval_sec)
    finally:
        # Flush buffered records and release the producer before exit.
        publisher.close()

    logger.info("Login Generator stopped — %d events published total", published)


if __name__ == "__main__":
    main()
