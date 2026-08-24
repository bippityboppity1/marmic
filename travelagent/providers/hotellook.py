"""Hotellook hotel prices (Travelpayouts family).

Serves cached nightly prices for a location and date range. It explicitly
does not check room availability, so these are CACHED — good for picking a
neighborhood and a tier, not for promising a room exists.

Contract (verified Aug 2026):
  GET https://engine.hotellook.com/api/v2/cache.json
  params: location, checkIn, checkOut, currency, limit, token
  Rate limits come back in X-Ratelimit-* headers.
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

    @property
    def configured(self) -> bool:
        # The endpoint answers without a token at a much lower rate limit,
        # so it is usable unconfigured — just say so honestly in the hint.
        return True

    def setup_hint(self) -> str:
        if self.config.travelpayouts_token:
            return "Using TRAVELPAYOUTS_TOKEN."
        return (
            "Works without a token at a reduced rate limit. Set "
            "TRAVELPAYOUTS_TOKEN to lift it."
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
