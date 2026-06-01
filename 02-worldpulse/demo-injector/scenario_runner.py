"""Thread-safe state holder for the demo server's single-scenario slot.

The previous design acquired a `threading.Lock` in the HTTP handler thread
and released it from the worker thread spawned to actually run the scenario
— a CPython misuse that can corrupt the lock and lets two requests race
between the membership check and the slot assignment.

This module centralises that logic in one place:

  * `try_start(name)` atomically claims the slot. Lock acquire and release
    happen on the same thread.
  * `finish()` clears the slot. Idempotent; safe to call from the worker
    thread.
  * `active()` returns the currently running scenario name (or None).

All public methods are short critical sections — nothing IO-bound runs
under the lock.
"""

from __future__ import annotations

import threading


class ScenarioRunner:
    """Bounded single-slot scheduler for demo scenarios."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active: str | None = None

    def active(self) -> str | None:
        """Return the currently running scenario name (or None)."""
        with self._lock:
            return self._active

    def try_start(self, name: str) -> bool:
        """Atomically claim the slot for `name`.

        Returns True if the slot was free (caller now owns it and must
        eventually call `finish()`). Returns False if another scenario is
        already running.
        """
        if not name:
            raise ValueError("scenario name must be non-empty")
        with self._lock:
            if self._active is not None:
                return False
            self._active = name
            return True

    def finish(self) -> None:
        """Release the slot. Idempotent."""
        with self._lock:
            self._active = None
