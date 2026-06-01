"""
HTTP server mode for the Demo Injector.

Exposes scenario triggers as POST endpoints so the dashboard can fire
them without a terminal. Runs alongside the existing CLI — same scenarios,
same EventPublisher, just triggered via HTTP instead of argv.

Endpoints:
    GET  /scenarios                → list available scenarios
    POST /scenarios/{name}         → run a scenario (optional JSON body: {"burstSize": 5})
    POST /reset                    → clear dashboard state before a new scenario
    GET  /health                   → liveness check

Usage:
    python demo_server.py                     # starts on port 8112
    python demo_server.py --port 8113         # custom port
"""

import json
import logging
import os
import threading
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone
from http.server import HTTPServer, BaseHTTPRequestHandler

from event_publisher import EventPublisher
from demo_injector import SCENARIOS, run_scenario
from scenario_runner import ScenarioRunner

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("demo-server")

KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
QUERY_SERVICE_URL = os.environ.get("QUERY_SERVICE_URL", "http://stream-processing:8097")
SOURCE_NAME = "WorldPulse-DemoInjector"
PORT = int(os.environ.get("DEMO_SERVER_PORT", "8112"))

PURGE_TOPICS = ["correlated-alerts", "alerts-enriched", "alerts-with-metadata"]

SCENARIO_LABELS = {
    "iran-oil":     "Trump triggers oil crisis",
    "ai-model":     "Musk announces AGI",
    "crypto-crash": "Crypto exchange hacked",
    "mass-signup":  "22 users sign up",
    "login-burst":  "50 logins across 6 cities",
    "mixed":        "Light up everything",
}

publisher = None
runner = ScenarioRunner()


class DemoHandler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/scenarios":
            self._list_scenarios()
        elif self.path == "/health":
            self._send_json(200, {"status": "ok", "active": runner.active()})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/reset":
            self._reset()
        elif self.path.startswith("/scenarios/"):
            name = self.path.split("/scenarios/", 1)[1].strip("/")
            self._run_scenario(name)
        else:
            self._send_json(404, {"error": "not found"})

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors_headers()
        self.end_headers()

    def _reset(self):
        """Clear dashboard state: purge Kafka topics + reset the QueryService buffer."""
        logger.info("Demo reset requested")
        results = {}

        # 1. Purge intermediate Kafka topics by temporarily setting retention to 1ms.
        #    This causes Kafka to delete all existing log segments, then we restore
        #    the original retention so new events are kept normally.
        try:
            from kafka.admin import KafkaAdminClient, ConfigResource, ConfigResourceType
            import time as _time

            admin = KafkaAdminClient(bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS)

            purge_configs = [
                ConfigResource(ConfigResourceType.TOPIC, topic, configs={"retention.ms": "1"})
                for topic in PURGE_TOPICS
            ]
            admin.alter_configs(purge_configs)
            logger.info("Set retention.ms=1 on %s — waiting for segment cleanup", PURGE_TOPICS)
            _time.sleep(2)

            restore_configs = [
                ConfigResource(ConfigResourceType.TOPIC, topic, configs={"retention.ms": "-1"})
                for topic in PURGE_TOPICS
            ]
            admin.alter_configs(restore_configs)
            admin.close()

            logger.info("Restored default retention on %s", PURGE_TOPICS)
            results["topicsPurged"] = PURGE_TOPICS
        except Exception as e:
            logger.warning("Topic purge failed (non-fatal): %s", e)
            results["topicsPurgeError"] = str(e)

        # 2. Clear the QueryService correlated-alerts buffer
        try:
            req = urllib.request.Request(
                f"{QUERY_SERVICE_URL}/api/reset", method="POST",
                headers={"Content-Type": "application/json"},
                data=b"{}",
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                results["queryServiceReset"] = json.loads(resp.read())
        except Exception as e:
            logger.warning("QueryService reset failed (non-fatal): %s", e)
            results["queryServiceResetError"] = str(e)

        self._send_json(200, {"status": "reset", **results})

    def _list_scenarios(self):
        scenarios = []
        for name in sorted(SCENARIOS.keys()):
            factory = SCENARIOS[name]
            steps = factory()
            scenarios.append({
                "name": name,
                "label": SCENARIO_LABELS.get(name, name),
                "steps": len(steps),
            })
        self._send_json(200, scenarios)

    def _run_scenario(self, name):
        if name not in SCENARIOS:
            self._send_json(404, {"error": f"unknown scenario: {name}"})
            return

        # Atomically claim the single-scenario slot. The lock inside the
        # ScenarioRunner is acquired and released on this thread only; the
        # worker thread spawned below calls finish() (which acquires the
        # same lock cleanly in its own thread).
        if not runner.try_start(name):
            self._send_json(409, {
                "error": "another scenario is running",
                "active": runner.active(),
            })
            return

        try:
            body = self._read_body()
            burst_size = body.get("burstSize", 3) if body else 3
            interval_ms = body.get("intervalMs", 200) if body else 200

            self._send_json(202, {
                "status": "started",
                "scenario": name,
                "label": SCENARIO_LABELS.get(name, name),
                "burstSize": burst_size,
            })
        except Exception:
            # If we never managed to dispatch the worker, free the slot
            # before bubbling up so the next request isn't permanently
            # locked out.
            runner.finish()
            raise

        def run():
            try:
                factory = SCENARIOS[name]
                steps = factory()
                logger.info("Running scenario '%s' (%d steps, burst=%d)",
                            name, len(steps), burst_size)
                total = run_scenario(publisher, steps, burst_size, interval_ms)

                logger.info("Scenario complete — suppressed windows will emit when the next scenario advances stream time")

                logger.info("Scenario '%s' finished — %d events published", name, total)
            except Exception as e:
                logger.error("Scenario '%s' failed: %s", name, e)
            finally:
                runner.finish()

        threading.Thread(target=run, daemon=True).start()

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return None
        try:
            return json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, ValueError):
            return None

    def _send_json(self, code, data):
        body = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self._cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def log_message(self, format, *args):
        logger.info(format, *args)


def main():
    global publisher
    logger.info("Connecting to Kafka at %s", KAFKA_BOOTSTRAP_SERVERS)
    publisher = EventPublisher(KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    try:
        # Server construction can fail (e.g. port already in use); the
        # publisher must still be flushed and closed if that happens, so
        # the bind goes inside the same try/finally as serve_forever().
        server = HTTPServer(("0.0.0.0", PORT), DemoHandler)
        logger.info("Demo server listening on port %d", PORT)
        logger.info("Endpoints: GET /scenarios, POST /scenarios/{name}, GET /health")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            logger.info("Shutting down")
            server.shutdown()
    finally:
        publisher.close()


if __name__ == "__main__":
    main()
