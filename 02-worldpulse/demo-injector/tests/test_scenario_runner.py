"""Tests for ScenarioRunner — the demo server's single-scenario slot.

These tests pin down two contracts that were violated by the previous
implementation:
  1. Lock acquire and release happen on the same thread (`finish()` is
     idempotent and may be called from a worker thread; `try_start()` is
     called from the HTTP handler thread).
  2. Two concurrent `try_start()` calls cannot both win — exactly one
     wins and the other gets False.
"""

import sys
import os
import threading

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from scenario_runner import ScenarioRunner


class TestActive:
    def test_starts_inactive(self):
        runner = ScenarioRunner()
        assert runner.active() is None

    def test_returns_running_scenario_name(self):
        runner = ScenarioRunner()
        runner.try_start("iran-oil")
        assert runner.active() == "iran-oil"


class TestTryStart:
    def test_succeeds_when_free(self):
        runner = ScenarioRunner()
        assert runner.try_start("crypto-crash") is True
        assert runner.active() == "crypto-crash"

    def test_fails_when_busy(self):
        runner = ScenarioRunner()
        runner.try_start("crypto-crash")
        assert runner.try_start("iran-oil") is False
        # Original scenario still owns the slot.
        assert runner.active() == "crypto-crash"

    def test_succeeds_again_after_finish(self):
        runner = ScenarioRunner()
        runner.try_start("crypto-crash")
        runner.finish()
        assert runner.try_start("iran-oil") is True
        assert runner.active() == "iran-oil"

    def test_rejects_empty_name(self):
        runner = ScenarioRunner()
        with pytest.raises(ValueError):
            runner.try_start("")


class TestFinish:
    def test_clears_slot(self):
        runner = ScenarioRunner()
        runner.try_start("crypto-crash")
        runner.finish()
        assert runner.active() is None

    def test_is_idempotent(self):
        runner = ScenarioRunner()
        runner.finish()
        runner.finish()
        assert runner.active() is None

    def test_finish_from_worker_thread_does_not_break_lock(self):
        """Regression test: in the old design the worker thread released a
        lock acquired by the HTTP handler thread, which is undefined for
        `threading.Lock`. Here we assert that finishing from a different
        thread leaves the runner in a usable state.
        """
        runner = ScenarioRunner()
        assert runner.try_start("crypto-crash") is True

        thread = threading.Thread(target=runner.finish)
        thread.start()
        thread.join(timeout=2.0)
        assert not thread.is_alive()

        # The runner must be usable again — try_start succeeds, active reads.
        assert runner.active() is None
        assert runner.try_start("iran-oil") is True
        assert runner.active() == "iran-oil"


class TestConcurrency:
    def test_only_one_concurrent_starter_wins(self):
        """Spin up many threads that all race to claim the slot; exactly
        one should win.
        """
        runner = ScenarioRunner()
        winners: list[bool] = []
        gate = threading.Event()
        lock = threading.Lock()

        def attempt(name: str) -> None:
            gate.wait()
            result = runner.try_start(name)
            with lock:
                winners.append(result)

        threads = [
            threading.Thread(target=attempt, args=(f"scenario-{i}",))
            for i in range(32)
        ]
        for t in threads:
            t.start()
        gate.set()
        for t in threads:
            t.join(timeout=5.0)
            assert not t.is_alive()

        assert sum(1 for w in winners if w) == 1
        assert sum(1 for w in winners if not w) == 31
        assert runner.active() is not None

    def test_finish_under_contention(self):
        """While many readers call active() and would-be starters call
        try_start(), a finish() must transition the slot cleanly.
        """
        runner = ScenarioRunner()
        runner.try_start("first")

        keep_running = True

        def reader() -> None:
            while keep_running:
                runner.active()

        readers = [threading.Thread(target=reader) for _ in range(4)]
        for r in readers:
            r.start()

        # Finish from yet another thread; subsequent try_start must succeed.
        finisher = threading.Thread(target=runner.finish)
        finisher.start()
        finisher.join(timeout=2.0)
        assert not finisher.is_alive()

        assert runner.try_start("second") is True
        assert runner.active() == "second"

        keep_running = False
        for r in readers:
            r.join(timeout=2.0)
            assert not r.is_alive()
