"""
Kafka event publisher for WorldPulse scanner services.

Produces CloudEvents-compatible messages to the 'worldpulse' Kafka topic,
using the exact same envelope format as the Java services (Message<T>).

Key detail: the event type is set BOTH in the JSON body ("type" field)
AND as a Kafka record header ("type" header). The Java MessageListener
uses @Header("type") to route events, so the header is mandatory.
"""

import json
import logging
import os
import time
from datetime import datetime, timezone
from uuid import uuid4

from kafka import KafkaProducer
from kafka.errors import NoBrokersAvailable

logger = logging.getLogger(__name__)

TOPIC_NAME = "worldpulse"


class EventPublisher:
    """Publishes CloudEvents-style messages to the worldpulse Kafka topic."""

    def __init__(self, bootstrap_servers: str, source_name: str):
        self.source_name = source_name
        self.producer = self._connect_with_retry(bootstrap_servers)

    def _connect_with_retry(self, bootstrap_servers: str, max_retries: int = 10) -> KafkaProducer:
        """Connect to Kafka with exponential backoff.
        """
        for attempt in range(1, max_retries + 1):
            try:
                producer = KafkaProducer(
                    bootstrap_servers=bootstrap_servers,
                    value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                    key_serializer=lambda k: k.encode("utf-8") if k else None,
                )
                logger.info(f"Connected to Kafka at {bootstrap_servers}")
                return producer
            except NoBrokersAvailable:
                wait_time = min(2 ** attempt, 30)  # cap at 30 seconds
                logger.warning(
                    f"Kafka not ready (attempt {attempt}/{max_retries}), "
                    f"retrying in {wait_time}s..."
                )
                time.sleep(wait_time)

        raise RuntimeError(f"Could not connect to Kafka at {bootstrap_servers} after {max_retries} attempts")

    def publish(self, event_type: str, data: dict, traceid: str | None = None) -> dict:
        """Publish an event to the worldpulse Kafka topic.

        The message envelope matches the Java Message<T> class exactly:
        - type, id, source, time, data, datacontenttype, specversion (CloudEvents core)
        - traceid, correlationid, group (WorldPulse extensions)

        Args:
            event_type: Event type string (e.g., "SocialTrendEvent")
            data: Event payload dictionary
            traceid: Optional trace ID for end-to-end tracking (auto-generated if None)

        Returns:
            The full message envelope that was published
        """
        message = {
            "type": event_type,
            "id": str(uuid4()),
            "source": self.source_name,
            "time": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "data": data,
            "datacontenttype": "application/json",
            "specversion": "1.0",
            "traceid": traceid or str(uuid4()),
            "correlationid": None,
            "group": "worldpulse",
        }

        headers = [("type", event_type.encode("utf-8"))]

        self.producer.send(
            TOPIC_NAME,
            value=message,
            headers=headers,
        )
        self.producer.flush()

        logger.info(f"Published {event_type} to '{TOPIC_NAME}': {data}")
        return message
