"""Parsing helpers shared by provider adapters."""

from __future__ import annotations

import re
from datetime import UTC, datetime

_ISO_DURATION = re.compile(
    r"^P(?:(?P<days>\d+)D)?(?:T(?:(?P<hours>\d+)H)?(?:(?P<minutes>\d+)M)?(?:(?P<seconds>\d+)S)?)?$"
)


def parse_iso_duration(value: str | None) -> int | None:
    """'PT7H35M' -> 455 minutes. Duffel and most NDC sources use this."""
    if not value:
        return None
    m = _ISO_DURATION.match(value.strip())
    if not m:
        return None
    parts = {k: int(v) for k, v in m.groupdict(default="0").items()}
    total = parts["days"] * 1440 + parts["hours"] * 60 + parts["minutes"]
    return total or None


def parse_dt(value: str | None) -> datetime | None:
    """Parse an ISO timestamp, tolerating 'Z' and missing timezone.

    Airline local times legitimately arrive without an offset; keep them
    naive rather than inventing UTC, because a fabricated timezone silently
    corrupts every connection-time calculation downstream.
    """
    if not value:
        return None
    raw = value.strip().replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(raw)
    except ValueError:
        for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
            try:
                return datetime.strptime(raw, fmt)
            except ValueError:
                continue
    return None


def utcnow() -> datetime:
    return datetime.now(UTC)
