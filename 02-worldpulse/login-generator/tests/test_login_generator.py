"""Unit tests for LoginGenerator — event shape, field constraints, and CLI parsing."""

import sys
import os
from datetime import datetime, timezone
from unittest.mock import MagicMock, patch, call

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from login_generator import (
    generate_login_event,
    LOCATIONS,
    DEVICE_TYPES,
    USER_IDS,
    main,
)


class TestGenerateLoginEvent:
    def test_returns_all_required_fields(self):
        event = generate_login_event()
        assert "userId" in event
        assert "location" in event
        assert "deviceType" in event
        assert "timestamp" in event

    def test_no_extra_fields(self):
        event = generate_login_event()
        assert set(event.keys()) == {"userId", "location", "deviceType", "timestamp"}

    def test_userid_from_known_pool(self):
        for _ in range(100):
            event = generate_login_event()
            assert event["userId"] in USER_IDS

    def test_location_from_valid_set(self):
        for _ in range(100):
            event = generate_login_event()
            assert event["location"] in LOCATIONS

    def test_device_type_from_valid_set(self):
        for _ in range(100):
            event = generate_login_event()
            assert event["deviceType"] in DEVICE_TYPES

    def test_timestamp_is_valid_utc_iso(self):
        event = generate_login_event()
        ts = event["timestamp"]
        assert ts.endswith("Z")
        parsed = datetime.fromisoformat(ts.replace("Z", "+00:00"))
        assert parsed.tzinfo is not None

    def test_events_have_randomness(self):
        locations = set()
        user_ids = set()
        for _ in range(200):
            event = generate_login_event()
            locations.add(event["location"])
            user_ids.add(event["userId"])
        assert len(locations) > 1
        assert len(user_ids) > 1


class TestConstantPools:
    def test_six_locations(self):
        assert len(LOCATIONS) == 6
        assert "Zurich" in LOCATIONS
        assert "Singapore" in LOCATIONS

    def test_three_device_types(self):
        assert DEVICE_TYPES == ["web", "mobile", "api"]

    def test_twenty_user_ids(self):
        assert len(USER_IDS) == 20
        assert USER_IDS[0] == "user-1"
        assert USER_IDS[-1] == "user-20"


class TestBurstMode:
    @patch("login_generator.EventPublisher")
    def test_publishes_exact_count(self, mock_publisher_cls):
        mock_publisher = MagicMock()
        mock_publisher_cls.return_value = mock_publisher

        with patch("sys.argv", ["login_generator.py", "--count", "5", "--interval-ms", "0"]):
            main()

        assert mock_publisher.publish.call_count == 5
        for c in mock_publisher.publish.call_args_list:
            assert c[0][0] == "LoginEvent"
            assert "userId" in c[0][1]
            assert "location" in c[0][1]

    @patch("login_generator.EventPublisher")
    def test_default_count_is_50(self, mock_publisher_cls):
        mock_publisher = MagicMock()
        mock_publisher_cls.return_value = mock_publisher

        with patch("sys.argv", ["login_generator.py", "--interval-ms", "0"]):
            with patch.dict(os.environ, {"LOGIN_COUNT": "50"}, clear=False):
                main()

        assert mock_publisher.publish.call_count == 50

    @patch("login_generator.EventPublisher")
    def test_event_type_is_login_event(self, mock_publisher_cls):
        mock_publisher = MagicMock()
        mock_publisher_cls.return_value = mock_publisher

        with patch("sys.argv", ["login_generator.py", "--count", "1", "--interval-ms", "0"]):
            main()

        event_type = mock_publisher.publish.call_args[0][0]
        assert event_type == "LoginEvent"


class TestEnvVarFallback:
    @patch("login_generator.EventPublisher")
    def test_count_from_env(self, mock_publisher_cls):
        mock_publisher = MagicMock()
        mock_publisher_cls.return_value = mock_publisher

        with patch("sys.argv", ["login_generator.py", "--interval-ms", "0"]):
            with patch.dict(os.environ, {"LOGIN_COUNT": "3"}, clear=False):
                main()

        assert mock_publisher.publish.call_count == 3

    @patch("login_generator.EventPublisher")
    def test_cli_overrides_env(self, mock_publisher_cls):
        mock_publisher = MagicMock()
        mock_publisher_cls.return_value = mock_publisher

        with patch("sys.argv", ["login_generator.py", "--count", "2", "--interval-ms", "0"]):
            with patch.dict(os.environ, {"LOGIN_COUNT": "100"}, clear=False):
                main()

        assert mock_publisher.publish.call_count == 2
