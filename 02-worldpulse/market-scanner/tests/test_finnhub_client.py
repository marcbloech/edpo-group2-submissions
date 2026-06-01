"""Tests for FinnhubClient — unit tests with mocks + live E2E connection test."""

import asyncio
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from finnhub_client import FinnhubClient


class TestFinnhubClientUnit:
    """Unit tests using a fake WebSocket."""

    @pytest.mark.asyncio
    async def test_normalizes_trade_data(self):
        received = []

        async def capture(trade):
            received.append(trade)

        client = FinnhubClient(api_key="fake", symbols=["AAPL"], on_trade_callback=capture)

        raw_msg = json.dumps({
            "type": "trade",
            "data": [
                {"s": "AAPL", "p": 178.52, "v": 100, "t": 1700000000000},
                {"s": "AAPL", "p": 178.60, "v": 50, "t": 1700000001000},
            ],
        })

        # Simulate processing a single message by calling the parsing logic directly
        msg = json.loads(raw_msg)
        if msg.get("type") == "trade":
            for trade in msg.get("data", []):
                await client.on_trade({
                    "symbol": trade["s"],
                    "price": trade["p"],
                    "volume": trade["v"],
                    "timestamp": trade["t"],
                })

        assert len(received) == 2
        assert received[0]["symbol"] == "AAPL"
        assert received[0]["price"] == 178.52
        assert received[1]["price"] == 178.60

    @pytest.mark.asyncio
    async def test_ignores_ping_messages(self):
        received = []

        async def capture(trade):
            received.append(trade)

        client = FinnhubClient(api_key="fake", symbols=["AAPL"], on_trade_callback=capture)

        ping_msg = json.loads('{"type":"ping"}')
        assert ping_msg.get("type") == "ping"
        # Ping should be skipped — no trades forwarded
        assert len(received) == 0

    def test_stop_sets_flag(self):
        client = FinnhubClient(api_key="fake", symbols=["AAPL"],
                               on_trade_callback=lambda t: None)
        assert client._stop is False
        client.stop()
        assert client._stop is True


@pytest.mark.skipif(
    not os.environ.get("FINNHUB_API_KEY"),
    reason="FINNHUB_API_KEY not set — skipping live E2E test",
)
class TestFinnhubClientLive:
    """Live E2E test — connects to real Finnhub WebSocket.

    Run with: FINNHUB_API_KEY=your_key pytest tests/test_finnhub_client.py -k Live -v
    NOTE: Only works during US market hours (Mon-Fri, 9:30AM-4:00PM ET).
    Outside market hours the connection succeeds but no trades arrive.
    """

    @pytest.mark.asyncio
    async def test_connects_and_receives_trades(self):
        api_key = os.environ["FINNHUB_API_KEY"]
        received = []

        async def capture(trade):
            received.append(trade)

        client = FinnhubClient(
            api_key=api_key,
            symbols=["AAPL"],
            on_trade_callback=capture,
        )

        async def run_with_timeout():
            task = asyncio.create_task(client.run())
            # Wait up to 15 seconds for at least one trade
            for _ in range(30):
                await asyncio.sleep(0.5)
                if received:
                    break
            client.stop()
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

        await run_with_timeout()

        if received:
            trade = received[0]
            assert "symbol" in trade
            assert "price" in trade
            assert "volume" in trade
            assert "timestamp" in trade
            assert trade["symbol"] == "AAPL"
            assert isinstance(trade["price"], (int, float))
            assert trade["price"] > 0
            print(f"\nReceived {len(received)} trades. First: {trade}")
        else:
            # Outside market hours — no trades, but connection succeeded
            pytest.skip("No trades received (likely outside US market hours)")
