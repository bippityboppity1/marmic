"""Shared test scaffolding.

Live egress to travel APIs is unavailable in CI, so every provider is tested
against a recorded response. That is the right default anyway: it pins the
contract, so if a provider changes its shape the test fails loudly instead of
the tool quietly returning nothing.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from travelagent.config import Config

FIXTURES = Path(__file__).parent / "fixtures"


def load_json(name: str) -> Any:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def load_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


class FakeHttp:
    """Stands in for HttpClient, returning canned payloads in order."""

    def __init__(self, *payloads: Any) -> None:
        self._payloads = list(payloads)
        self.calls: list[dict[str, Any]] = []

    async def request_json(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        params: dict[str, Any] | None = None,
        json: Any = None,
    ) -> Any:
        self.calls.append(
            {"method": method, "url": url, "headers": headers, "params": params, "json": json}
        )
        if not self._payloads:
            raise AssertionError(f"unexpected extra request to {url}")
        return self._payloads.pop(0)


class FailingHttp:
    def __init__(self, exc: Exception) -> None:
        self._exc = exc

    async def request_json(self, *args: Any, **kwargs: Any) -> Any:
        raise self._exc


@pytest.fixture
def config(tmp_path: Path) -> Config:
    return Config(
        duffel_token="duffel_test_abc123",
        travelpayouts_token="tp_token_xyz",
        travelpayouts_marker="12345",
        currency="EUR",
        cache_path=tmp_path / "cache.sqlite3",
        cache_enabled=False,
    )
