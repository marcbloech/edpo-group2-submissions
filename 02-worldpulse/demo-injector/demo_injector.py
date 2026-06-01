"""
WorldPulse Demo Injector.

Plays back pre-built event sequences ("scenarios") into the 'worldpulse' Kafka
topic so the full stream-processing pipeline lights up during a demo.

Usage:
    python demo_injector.py <scenario> [--burst-size N] [--interval-ms MS]

A scenario is a list of (event_type, data_dict, delay_ms) tuples. The engine
loops through the list, publishes each event, and sleeps `delay_ms` before
moving on. `--burst-size N` repeats market/social events N times to amplify
volume so the windowed aggregations (alert-stats, signup-counts) produce
visible output during demos.

Available scenarios:
    iran-oil       Energy crisis: oil futures + social panic + premium signups
    ai-model       AI hype: Grok-X chatter, MSFT/GOOGL moves, AI regulation backlash
    crypto-crash   Exchange hack: BTC/ETH plunge + viral social spike
    mass-signup    22 signups + payments + logins across all tiers
    login-burst    50 logins across 6 locations
    mixed          Sampler that exercises every downstream pipeline
    smoke-test     Minimal engine-validation probe (not a demo scenario)
"""

import argparse
import logging
import os
import random
import signal
import sys
import time
from datetime import datetime, timedelta, timezone
from typing import Callable

from event_publisher import EventPublisher

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("demo-injector")

KAFKA_BOOTSTRAP_SERVERS = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SOURCE_NAME = "WorldPulse-DemoInjector"

running = True


def handle_shutdown(signum, frame):
    global running
    logger.info("Shutdown signal received, finishing...")
    running = False


# ─── Scenario registry ────────────────────────────────────────────────────────
# A scenario is a list of (event_type, data_dict, delay_ms) tuples.
# The registry maps a CLI name → a factory function that returns that list.
# We store a *factory* (not the list) so values like timestamps or random IDs
# get re-evaluated each run — important for scenarios that use datetime.now()
# or random.choice().

ScenarioStep = tuple[str, dict, int]
ScenarioFactory = Callable[[], list[ScenarioStep]]

SCENARIOS: dict[str, ScenarioFactory] = {}


def register_scenario(name: str):
    """Decorator: a no-arg function returning [(event_type, data, delay_ms), ...]
    becomes a registered scenario callable from the CLI.

    Example (E3 will fill these in):

        @register_scenario("iran-oil")
        def _iran_oil() -> list[ScenarioStep]:
            return [
                ("MarketAlertEvent", {"symbol": "CL=F", ...}, 500),
                ("SocialTrendEvent", {"topic": "Iran oil strike", ...}, 200),
            ]
    """
    def decorator(func: ScenarioFactory):
        SCENARIOS[name] = func
        return func
    return decorator


# ─── Engine ──────────────────────────────────────────────────────────────────
# Event types that get bursted by --burst-size. These feed windowed aggregations
# (alert-stats), so a single event in a 1-minute window doesn't show much.
# Multiplying them gives the windows enough volume to produce interesting
# avgChangePercent / alertCount values during demos.
BURSTABLE_TYPES = {"MarketAlertEvent", "SocialTrendEvent"}


def run_scenario(publisher: EventPublisher,
                 steps: list[ScenarioStep],
                 burst_size: int,
                 interval_ms: int) -> int:
    """Replay a scenario step-list into Kafka.

    For each (event_type, data, delay_ms) step:
      1. Publish the event. If the type is in BURSTABLE_TYPES, repeat
         `burst_size` times with `interval_ms` between repeats (so each copy
         lands at a distinct timestamp — important for windowed aggregations
         that key on event time).
      2. Sleep `delay_ms` before the next step.

    Returns the number of events actually published. Honours the global
    `running` flag so SIGINT/SIGTERM cuts the scenario cleanly between steps.
    """
    inter_burst_sec = interval_ms / 1000.0
    total = 0

    for step_idx, (event_type, data, delay_ms) in enumerate(steps, start=1):
        if not running:
            logger.info("Scenario interrupted at step %d/%d", step_idx, len(steps))
            return total

        repeat = burst_size if event_type in BURSTABLE_TYPES else 1
        for i in range(repeat):
            publisher.publish(event_type, data)
            total += 1
            if i < repeat - 1:
                time.sleep(inter_burst_sec)

        # TODO (learn 1 — easy): the inter-step delay is the same for every
        #   repeat above. If you want each repeat in a burst to carry a
        #   slightly different timestamp/symbol, mutate `data` per-iteration
        #   here (e.g. data = {**data, "alertId": f"{data['alertId']}-{i}"}).
        #   Useful when bursts of the *same* event collapse into one record
        #   downstream because they share a key.

        time.sleep(delay_ms / 1000.0)

    return total


# ─── E3: Demo scenarios ──────────────────────────────────────────────────────
# All symbols/topics below match entries in stream-processing's
# symbol-metadata.json so the C5 KStream-GlobalKTable join populates `sector`
# and C6's KStream-KStream windowed correlation can fire.
#
# changePercent / postCount values are chosen to land in deliberate risk tiers
# per EventEnrichmentTopology:
#   market: |Δ%| ≥ 8 → CRITICAL, ≥ 5 → HIGH, ≥ 3 → MEDIUM, else LOW
#   social: postCount > 100 → CRITICAL, > 50 → HIGH, > 10 → MEDIUM, else LOW


@register_scenario("iran-oil")
def _iran_oil() -> list[ScenarioStep]:
    """Energy crisis (Plan §11): keyword chatter on Bluesky → oil futures spike
    → related energy stocks move → tech knock-on → premium signups.

    Exercises C6 (market+social correlation in Energy sector), C5 (metadata
    enrichment for CL=F / XOM / AMZN), alert-stats windowed aggregation.
    """
    steps: list[ScenarioStep] = []

    # 5 social signals: "Iran oil strike"
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"iran-oil-soc-strike-{i+1}",
            "topic": "Iran oil strike",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(5)
    ]

    # Oil futures spike — CRITICAL
    steps.append(("MarketAlertEvent", {
        "alertId": "iran-oil-mkt-clf",
        "symbol": "CL=F",
        "alertType": "PRICE_SPIKE",
        "changePercent": 8.3,
    }, 300))

    # Energy stock follows — MEDIUM
    steps.append(("MarketAlertEvent", {
        "alertId": "iran-oil-mkt-xom",
        "symbol": "XOM",
        "alertType": "PRICE_SPIKE",
        "changePercent": 4.1,
    }, 300))

    # 5 social signals: "oil prices"
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"iran-oil-soc-prices-{i+1}",
            "topic": "oil prices",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(5)
    ]

    # Knock-on tech-stock drop — LOW
    steps.append(("MarketAlertEvent", {
        "alertId": "iran-oil-mkt-amzn",
        "symbol": "AMZN",
        "alertType": "PRICE_DROP",
        "changePercent": -2.1,
    }, 300))

    # Downstream effect: 2 PREMIUM signups
    steps += [
        ("SignupRequestedEvent", {
            "userId": f"iran-oil-user-{i+1}",
            "name": f"Crisis Premium User {i+1}",
            "email": f"crisis{i+1}@worldpulse.demo",
            "tier": "PREMIUM",
        }, 500)
        for i in range(2)
    ]

    return steps


@register_scenario("ai-model")
def _ai_model() -> list[ScenarioStep]:
    """AI hype cycle (Plan §11): Grok-X release rumours → MSFT up / GOOGL down
    → AI regulation backlash → mixed-tier signups.

    Exercises C6 correlation in Technology sector across two distinct social
    topics (Grok-X release, AI regulation).
    """
    steps: list[ScenarioStep] = []

    # 8 social signals: "Grok-X release"
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"ai-soc-gpt5-{i+1}",
            "topic": "Grok-X release",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 80)
        for i in range(8)
    ]

    # MSFT bump — MEDIUM
    steps.append(("MarketAlertEvent", {
        "alertId": "ai-mkt-msft",
        "symbol": "MSFT",
        "alertType": "PRICE_SPIKE",
        "changePercent": 3.7,
    }, 300))

    # GOOGL drop on competitive pressure — LOW
    steps.append(("MarketAlertEvent", {
        "alertId": "ai-mkt-googl",
        "symbol": "GOOGL",
        "alertType": "PRICE_DROP",
        "changePercent": -2.4,
    }, 300))

    # 5 social signals: "AI regulation" backlash
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"ai-soc-reg-{i+1}",
            "topic": "AI regulation",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(5)
    ]

    # Mixed-tier signups (one of each non-FREE tier)
    for tier, label in [("BASIC", "Hobbyist"), ("PREMIUM", "Pro"), ("ENTERPRISE", "Corp")]:
        steps.append(("SignupRequestedEvent", {
            "userId": f"ai-user-{tier.lower()}",
            "name": f"AI {label} User",
            "email": f"ai-{tier.lower()}@worldpulse.demo",
            "tier": tier,
        }, 400))

    return steps


@register_scenario("crypto-crash")
def _crypto_crash() -> list[ScenarioStep]:
    """Exchange hack: panic posts → BTC plunges → ETH follows → viral social
    spike (single CRITICAL post) → second wave of chatter → panic signups.

    Demonstrates CRITICAL risk on both market (≥8% drop) and social (>100
    postCount) sides simultaneously.
    """
    steps: list[ScenarioStep] = []

    # Initial panic posts
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"crypto-soc-init-{i+1}",
            "topic": "crypto crash",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(3)
    ]

    # BTC plunge — CRITICAL
    steps.append(("MarketAlertEvent", {
        "alertId": "crypto-mkt-btc",
        "symbol": "BTC-USD",
        "alertType": "PRICE_DROP",
        "changePercent": -12.4,
    }, 300))

    # ETH follows — CRITICAL
    steps.append(("MarketAlertEvent", {
        "alertId": "crypto-mkt-eth",
        "symbol": "ETH-USD",
        "alertType": "PRICE_DROP",
        "changePercent": -9.8,
    }, 300))

    # Single viral burst — CRITICAL social risk (postCount > 100)
    steps.append(("SocialTrendEvent", {
        "alertId": "crypto-soc-viral",
        "topic": "crypto crash",
        "alertType": "TREND_SPIKE",
        "postCount": 250,
    }, 200))

    # Second wave of low-volume posts (volume in window)
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"crypto-soc-wave-{i+1}",
            "topic": "crypto crash",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(5)
    ]

    # 3 panic signups across tiers
    for i, tier in enumerate(["FREE", "BASIC", "PREMIUM"]):
        steps.append(("SignupRequestedEvent", {
            "userId": f"crypto-user-{i+1}",
            "name": f"Crypto Refugee {i+1}",
            "email": f"crypto{i+1}@worldpulse.demo",
            "tier": tier,
        }, 400))

    return steps


@register_scenario("mass-signup")
def _mass_signup() -> list[ScenarioStep]:
    """22 signups across all four tiers with realistic follow-up events.

    Tier distribution mimics a realistic funnel: 8 FREE → 6 BASIC → 6 PREMIUM
    → 2 ENTERPRISE. Each signup is followed by a PaymentReceivedEvent (paid
    tiers only) and a LoginEvent, so the user-dashboard KTable-KTable join
    shows data on both sides and the login-stats window lights up.

    Demonstrates: suppressed signup-counts aggregation, UserActivity +
    PaymentSummary KTable-KTable join, and login-stats windowed count.
    """
    rng = random.Random(7)
    tier_plan = (["FREE"] * 8) + (["BASIC"] * 6) + (["PREMIUM"] * 6) + (["ENTERPRISE"] * 2)
    locations = ["Zurich", "Geneva", "New York", "London", "Tokyo", "Singapore"]
    steps: list[ScenarioStep] = []

    for i, tier in enumerate(tier_plan):
        user_id = f"mass-user-{i+1:03d}"
        name = f"Mass Signup {i+1}"
        email = f"mass{i+1}@worldpulse.demo"

        # 1) Signup
        steps.append(("SignupRequestedEvent", {
            "userId": user_id,
            "name": name,
            "email": email,
            "tier": tier,
        }, 120))

        # 2) Payment (paid tiers only — FREE users never pay)
        if tier != "FREE":
            amounts = {"BASIC": 900, "PREMIUM": 1900, "ENTERPRISE": 9900}
            steps.append(("PaymentReceivedEvent", {
                "userId": user_id,
                "email": email,
                "name": name,
                "tier": tier,
                "paymentAmount": amounts[tier],
                "transactionId": f"txn-mass-{i+1:03d}",
            }, 80))

        # 3) Login (all users log in after signing up)
        steps.append(("LoginEvent", {
            "userId": user_id,
            "location": rng.choice(locations),
            "deviceType": rng.choice(["web", "mobile"]),
        }, 80))

    return steps


@register_scenario("login-burst")
def _login_burst() -> list[ScenarioStep]:
    """50 LoginEvents spread across 6 locations × 3 device types × 20 users.

    Exercises the login-stats windowed aggregation (1-hour window, NO suppress
    — emits intermediate counts in real-time). Uses a seeded RNG so the
    sequence is deterministic across runs.
    """
    rng = random.Random(42)
    locations = ["Zurich", "Geneva", "New York", "London", "Tokyo", "Singapore"]
    devices = ["web", "mobile", "api"]
    user_pool = [f"login-user-{i+1:03d}" for i in range(20)]

    return [
        ("LoginEvent", {
            "userId": rng.choice(user_pool),
            "location": rng.choice(locations),
            "deviceType": rng.choice(devices),
        }, 80)
        for _ in range(50)
    ]


@register_scenario("mixed")
def _mixed() -> list[ScenarioStep]:
    """Condensed sampler — touches every downstream pipeline:
    market+social correlation across Energy / Technology / Crypto sectors,
    signups across all tiers, and a login flurry across all 6 locations.
    Good as the "default demo" when you want one button that lights up
    everything.
    """
    steps: list[ScenarioStep] = []

    # Energy: 3 social → 1 market (HIGH)
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"mix-soc-oil-{i+1}",
            "topic": "Iran oil strike",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(3)
    ]
    steps.append(("MarketAlertEvent", {
        "alertId": "mix-mkt-clf",
        "symbol": "CL=F",
        "alertType": "PRICE_SPIKE",
        "changePercent": 6.2,
    }, 200))

    # Technology: 3 social → 1 market (MEDIUM)
    steps += [
        ("SocialTrendEvent", {
            "alertId": f"mix-soc-ai-{i+1}",
            "topic": "Grok-X release",
            "alertType": "KEYWORD_MATCH",
            "postCount": 1,
        }, 100)
        for i in range(3)
    ]
    steps.append(("MarketAlertEvent", {
        "alertId": "mix-mkt-msft",
        "symbol": "MSFT",
        "alertType": "PRICE_SPIKE",
        "changePercent": 3.5,
    }, 200))

    # Crypto: single CRITICAL market
    steps.append(("MarketAlertEvent", {
        "alertId": "mix-mkt-btc",
        "symbol": "BTC-USD",
        "alertType": "PRICE_DROP",
        "changePercent": -8.7,
    }, 200))

    # Signups across all four tiers
    for tier in ["FREE", "BASIC", "PREMIUM", "ENTERPRISE"]:
        steps.append(("SignupRequestedEvent", {
            "userId": f"mix-user-{tier.lower()}",
            "name": f"Mix {tier.title()}",
            "email": f"mix-{tier.lower()}@worldpulse.demo",
            "tier": tier,
        }, 150))

    # One login per location
    for i, loc in enumerate(["Zurich", "Geneva", "New York", "London", "Tokyo", "Singapore"]):
        steps.append(("LoginEvent", {
            "userId": f"mix-login-{i+1}",
            "location": loc,
            "deviceType": "web",
        }, 100))

    return steps


# TODO (learn 4 — medium): write a `--list-detail` flag that prints the step
#   count and event-type breakdown for each registered scenario (e.g.
#   "iran-oil: 15 steps — 10 social, 3 market, 2 signup"). Hint: iterate
#   SCENARIOS, call each factory, then Counter() the first element of each
#   tuple. Useful for confirming a scenario's volume before running it.


# ─── Built-in smoke-test scenario ────────────────────────────────────────────
# Tiny 2-event sequence — keep it as a "is the pipeline alive?" probe before
# each demo. Not a real demo scenario — those are above.

@register_scenario("smoke-test")
def _smoke_test() -> list[ScenarioStep]:
    return [
        ("MarketAlertEvent", {
            "alertId": "smoke-mkt-001",
            "symbol": "AAPL",
            "alertType": "PRICE_DROP",
            "description": "Smoke-test market alert (engine verification only)",
            "changePercent": -2.5,
            "timestamp": "2026-05-10T12:00:00Z",
        }, 500),
        ("SocialTrendEvent", {
            "alertId": "smoke-soc-001",
            "topic": "Grok-X release",
            "alertType": "TREND_SPIKE",
            "description": "Smoke-test social trend (engine verification only)",
            "postCount": 42,
            "timestamp": "2026-05-10T12:00:00Z",
        }, 500),
    ]


# TODO (learn 2 — medium): add a `--dry-run` flag that logs each event but
#   does NOT call publisher.publish(). Useful for sanity-checking new
#   scenarios before they hit Kafka. Hint: thread `dry_run: bool` through
#   run_scenario() and skip the publish call when set.

# TODO (learn 3 — medium): the engine treats every step as a single
#   publish-then-sleep. For E3's "mass-signup" scenario you may want to
#   *generate* steps inline (e.g. 20 different SignupRequestedEvents from
#   a list comprehension). Try writing a scenario factory that returns
#   `[("SignupRequestedEvent", make_signup(i), 100) for i in range(20)]`
#   — no engine changes needed.


# ─── CLI ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="WorldPulse Demo Injector — replays event scenarios into Kafka",
    )
    parser.add_argument(
        "scenario",
        nargs="?",
        choices=sorted(SCENARIOS.keys()) or None,
        help="Scenario to run",
    )
    parser.add_argument("--burst-size", type=int, default=1,
                        help="Repeat market/social events N times (amplifies volume "
                             "for windowed aggregations). Default: 1")
    parser.add_argument("--interval-ms", type=int, default=200,
                        help="Gap between repeats inside a burst, in ms. Default: 200")
    parser.add_argument("--list", action="store_true",
                        help="List available scenarios and exit")
    args = parser.parse_args()

    if args.list or not args.scenario:
        if not SCENARIOS:
            print("No scenarios registered.")
        else:
            print("Available scenarios:")
            for name in sorted(SCENARIOS):
                print(f"  - {name}")
        sys.exit(0 if args.list else 1)

    signal.signal(signal.SIGINT, handle_shutdown)
    signal.signal(signal.SIGTERM, handle_shutdown)

    logger.info(
        "Demo Injector starting — bootstrap=%s scenario=%s burst-size=%d interval=%dms",
        KAFKA_BOOTSTRAP_SERVERS, args.scenario, args.burst_size, args.interval_ms,
    )

    publisher = EventPublisher(KAFKA_BOOTSTRAP_SERVERS, SOURCE_NAME)

    try:
        factory = SCENARIOS.get(args.scenario)
        if factory is None:
            logger.error("Scenario '%s' not registered", args.scenario)
            sys.exit(2)

        steps = factory()
        logger.info("Scenario '%s' has %d steps", args.scenario, len(steps))

        total = run_scenario(publisher, steps, args.burst_size, args.interval_ms)

        # Note: suppressed windows (signup-counts, correlated-alerts-summary)
        # only emit when stream time advances past window_end + grace_period.
        # Stream time advances when the NEXT scenario's events arrive, so
        # suppressed results from this scenario appear once the next one runs.

        logger.info("Demo Injector finished — scenario '%s', %d events published",
                    args.scenario, total)
    finally:
        # Flush buffered records and release the producer before exit.
        publisher.close()


if __name__ == "__main__":
    main()
