"""Provider assembly. One place that knows what sources exist."""

from __future__ import annotations

from ..config import Config
from .base import Provider
from .duffel import DuffelProvider
from .hotellook import HotellookProvider
from .travelpayouts import TravelpayoutsProvider


def all_providers(config: Config) -> list[Provider]:
    """Every known provider, configured or not.

    Scrapers are imported lazily so the package stays usable when playwright
    is not installed.
    """
    providers: list[Provider] = [
        DuffelProvider(config),
        TravelpayoutsProvider(config),
        HotellookProvider(config),
    ]

    try:
        from ..scrapers.booking import BookingScraper
        from ..scrapers.google_flights import GoogleFlightsScraper
    except ImportError:  # pragma: no cover - optional path
        return providers

    providers.extend([GoogleFlightsScraper(config), BookingScraper(config)])
    return providers


def active_providers(config: Config, kind: str) -> list[Provider]:
    """Configured providers that support `kind` ('flights' or 'hotels')."""
    attr = f"supports_{kind}"
    return [p for p in all_providers(config) if getattr(p, attr, False) and p.configured]
