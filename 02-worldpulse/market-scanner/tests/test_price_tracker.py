"""Unit tests for PriceTracker — sliding window + threshold detection."""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from price_tracker import PriceTracker


class TestRecordTrade:
    def test_records_trade_and_updates_latest_price(self):
        pt = PriceTracker(["AAPL"], window_size=60)
        pt.record_trade("AAPL", 150.0, timestamp=1000.0)
        assert len(pt._prices["AAPL"]) == 1
        assert pt._latest_price["AAPL"] == 150.0

    def test_records_multiple_trades(self):
        pt = PriceTracker(["AAPL"], window_size=60)
        pt.record_trade("AAPL", 150.0, timestamp=1000.0)
        pt.record_trade("AAPL", 155.0, timestamp=1001.0)
        pt.record_trade("AAPL", 148.0, timestamp=1002.0)
        assert len(pt._prices["AAPL"]) == 3
        assert pt._latest_price["AAPL"] == 148.0


class TestEvaluateEviction:
    def test_evicts_old_entries(self):
        pt = PriceTracker(["AAPL"], window_size=60)
        pt.record_trade("AAPL", 150.0, timestamp=1000.0)
        pt.record_trade("AAPL", 151.0, timestamp=1070.0)
        pt.evaluate(now=1070.0)
        assert len(pt._prices["AAPL"]) == 1

    def test_skips_symbol_with_fewer_than_2_entries(self):
        pt = PriceTracker(["AAPL"], window_size=60)
        pt.record_trade("AAPL", 150.0, timestamp=1000.0)
        alerts = pt.evaluate(now=1010.0)
        assert alerts == []


class TestPriceDrop:
    def test_detects_price_drop(self):
        pt = PriceTracker(["AAPL"], window_size=60, drop_threshold=3.0)
        pt.record_trade("AAPL", 100.0, timestamp=1000.0)
        pt.record_trade("AAPL", 96.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert len(alerts) == 1
        assert alerts[0]["alertType"] == "PRICE_DROP"
        assert alerts[0]["symbol"] == "AAPL"
        assert alerts[0]["changePercent"] == -4.0
        assert alerts[0]["currentPrice"] == 96.0

    def test_no_alert_below_threshold(self):
        pt = PriceTracker(["AAPL"], window_size=60, drop_threshold=3.0)
        pt.record_trade("AAPL", 100.0, timestamp=1000.0)
        pt.record_trade("AAPL", 98.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert alerts == []


class TestPriceSpike:
    def test_detects_price_spike(self):
        pt = PriceTracker(["TSLA"], window_size=60, spike_threshold=5.0)
        pt.record_trade("TSLA", 100.0, timestamp=1000.0)
        pt.record_trade("TSLA", 106.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert len(alerts) == 1
        assert alerts[0]["alertType"] == "PRICE_SPIKE"
        assert alerts[0]["changePercent"] == 6.0

    def test_no_spike_below_threshold(self):
        pt = PriceTracker(["TSLA"], window_size=60, spike_threshold=5.0)
        pt.record_trade("TSLA", 100.0, timestamp=1000.0)
        pt.record_trade("TSLA", 104.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert alerts == []


class TestVolatility:
    def test_detects_volatility_without_net_change(self):
        pt = PriceTracker(["MSFT"], window_size=60, drop_threshold=3.0,
                          spike_threshold=5.0, volatility_threshold=8.0)
        pt.record_trade("MSFT", 100.0, timestamp=1000.0)
        pt.record_trade("MSFT", 110.0, timestamp=1020.0)
        pt.record_trade("MSFT", 101.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert len(alerts) == 1
        assert alerts[0]["alertType"] == "VOLATILITY"

    def test_drop_takes_priority_over_volatility(self):
        pt = PriceTracker(["MSFT"], window_size=60, drop_threshold=3.0,
                          spike_threshold=5.0, volatility_threshold=5.0)
        pt.record_trade("MSFT", 100.0, timestamp=1000.0)
        pt.record_trade("MSFT", 110.0, timestamp=1020.0)
        pt.record_trade("MSFT", 93.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert len(alerts) == 1
        assert alerts[0]["alertType"] == "PRICE_DROP"


class TestMultipleSymbols:
    def test_tracks_symbols_independently(self):
        pt = PriceTracker(["AAPL", "TSLA"], window_size=60,
                          drop_threshold=3.0, spike_threshold=5.0)
        pt.record_trade("AAPL", 100.0, timestamp=1000.0)
        pt.record_trade("AAPL", 96.0, timestamp=1050.0)
        pt.record_trade("TSLA", 200.0, timestamp=1000.0)
        pt.record_trade("TSLA", 212.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert len(alerts) == 2
        types = {a["symbol"]: a["alertType"] for a in alerts}
        assert types["AAPL"] == "PRICE_DROP"
        assert types["TSLA"] == "PRICE_SPIKE"


class TestAlertDescription:
    def test_description_contains_symbol_and_percentage(self):
        pt = PriceTracker(["AAPL"], window_size=300, drop_threshold=3.0)
        pt.record_trade("AAPL", 100.0, timestamp=1000.0)
        pt.record_trade("AAPL", 95.0, timestamp=1200.0)
        alerts = pt.evaluate(now=1200.0)
        desc = alerts[0]["description"]
        assert "AAPL" in desc
        assert "5.0%" in desc
        assert "300s" in desc


class TestNonPositivePriceGuard:
    """Real feeds shouldn't emit zero or negative ticks, but a single bad
    tick from a halted symbol or a serialisation glitch previously caused
    a divide-by-zero (or worse, a negative-denominator percentage) in
    evaluate(). The guard short-circuits those symbols.
    """

    def test_zero_oldest_price_does_not_raise(self):
        pt = PriceTracker(["BAD"], window_size=60, drop_threshold=3.0,
                          spike_threshold=5.0)
        pt.record_trade("BAD", 0.0, timestamp=1000.0)
        pt.record_trade("BAD", 5.0, timestamp=1050.0)
        # Must not raise ZeroDivisionError; must not emit a misleading alert.
        alerts = pt.evaluate(now=1050.0)
        assert alerts == []

    def test_negative_oldest_price_skipped(self):
        pt = PriceTracker(["BAD"], window_size=60, drop_threshold=3.0,
                          spike_threshold=5.0)
        pt.record_trade("BAD", -1.0, timestamp=1000.0)
        pt.record_trade("BAD", 10.0, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        assert alerts == []

    def test_zero_min_price_in_volatility_branch_skipped(self):
        # Net change small enough to fall through to the volatility branch,
        # but min price is zero — divide-by-zero would happen without guard.
        pt = PriceTracker(["BAD"], window_size=60, drop_threshold=3.0,
                          spike_threshold=5.0, volatility_threshold=2.0)
        pt.record_trade("BAD", 100.0, timestamp=1000.0)
        pt.record_trade("BAD", 0.0, timestamp=1020.0)
        pt.record_trade("BAD", 100.5, timestamp=1050.0)
        alerts = pt.evaluate(now=1050.0)
        # No volatility alert (would otherwise divide by min_p == 0).
        assert alerts == []
