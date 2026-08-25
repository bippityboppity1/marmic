"""Hotellook hotel prices (Travelpayouts family) — RETIRED.

Travelpayouts shut Hotellook down: the brand closed, the affiliate programme
closed, and the API stopped serving. Bookings were tracked only through
2025-10-20. Verified from this machine on 2026-08-26 — every path on
engine.hotellook.com returns an identical 146-byte nginx 404, including the
bare root, and yasen.hotellook.com behaves the same. A retired host, not a
moved endpoint: a changed response shape would still answer with JSON.

The contract below was written from documentation that described a live
service. The parser and its tests are kept deliberately — they still pin the
shape this API used to return, and they are the starting point if hotels are
ever re-sourced from another provider. Nothing calls them in production while
`retired` is True.

Former contract:
  GET https://engine.hotellook.com/api/v2/cache.json
  params: location, checkIn, checkOut, currency, limit, token
"""

from __future__ import annotations

from typing import Any
from urllib.parse import urlencode

from ..errors import ContractMismatch
from ..models import Freshness, HotelQuote, Money
from ..query import HotelSearch
from ._util import utcnow
from .base import Provider

BASE_URL = "https://engine.hotellook.com/api/v2"


class HotellookProvider(Provider):
    name = "hotellook"
    supports_hotels = True
    retired = True

    @property
    def configured(self) -> bool:
        # Never configurable again — the service it talks to is gone.
        return False

    def setup_hint(self) -> str:
        return (
            "Hotellook was shut down by Travelpayouts (API off since late "
            "2025); no token will revive it. Hotel search needs a new "
            "source — see the module docstring."
        )

    async def search_hotels(self, query: HotelSearch, http) -> list[HotelQuote]:
        params: dict[str, Any] = {
            "location": query.location,
            "checkIn": query.check_in.isoformat(),
            "checkOut": query.check_out.isoformat(),
            "currency": query.currency.lower(),
            "limit": query.limit,
        }
        if self.config.travelpayouts_token:
            params["token"] = self.config.travelpayouts_token

        payload = await http.request_json(
            "GET", f"{BASE_URL}/cache.json", params=params
        )
        return self._parse(payload, query)

    def _parse(self, payload: Any, query: HotelSearch) -> list[HotelQuote]:
        if payload is None:
            return []
        if isinstance(payload, dict) and payload.get("error"):
            raise ContractMismatch(f"Hotellook error: {payload['error']}")
        if not isinstance(payload, list):
            raise ContractMismatch(
                "Hotellook cache.json returned a non-list response — contract may have moved"
            )

        quotes: list[HotelQuote] = []
        for row in payload:
            if not isinstance(row, dict):
                continue
            total = row.get("priceFrom") or row.get("priceAvg")
            name = row.get("hotelName")
            if total is None or not name:
                continue

            loc = row.get("location") or {}
            quote = HotelQuote(
                provider=self.name,
                name=str(name),
                price_total=Money.of(total, query.currency),
                nights=query.nights,
                freshness=Freshness.CACHED,
                bookable=False,
                stars=_num(row.get("stars")),
                rating=_num(row.get("rating")),
                neighborhood=loc.get("name"),
                distance_km_center=_num(row.get("distance")),
                check_in=query.check_in,
                check_out=query.check_out,
                deep_link=self._deep_link(row, query),
                observed_at=utcnow(),
                raw=row,
            )
            if query.min_stars and (quote.stars or 0) < query.min_stars:
                continue
            if (
                query.max_price_per_night
                and quote.price_per_night
                and float(quote.price_per_night.amount) > query.max_price_per_night
            ):
                continue
            quotes.append(quote)
        return quotes

    def _deep_link(self, row: dict[str, Any], query: HotelSearch) -> str:
        params = {
            "checkIn": query.check_in.isoformat(),
            "checkOut": query.check_out.isoformat(),
            "adults": query.adults,
            "currency": query.currency.lower(),
        }
        hotel_id = row.get("hotelId")
        if hotel_id:
            params["hotelId"] = hotel_id
        else:
            params["destination"] = query.location
        if self.config.travelpayouts_marker:
            params["marker"] = self.config.travelpayouts_marker
        return f"https://search.hotellook.com/hotels?{urlencode(params)}"

    async def probe(self, http) -> str:
        params: dict[str, Any] = {
            "location": "Rome",
            "checkIn": "2026-10-10",
            "checkOut": "2026-10-12",
            "currency": "eur",
            "limit": 1,
        }
        if self.config.travelpayouts_token:
            params["token"] = self.config.travelpayouts_token
        payload = await http.request_json("GET", f"{BASE_URL}/cache.json", params=params)
        n = len(payload) if isinstance(payload, list) else 0
        tok = "with token" if self.config.travelpayouts_token else "anonymous"
        return f"reachable ({tok}, Rome probe returned {n} properties)"


def _num(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None
