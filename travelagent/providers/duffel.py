"""Duffel — live, bookable flight offers.

Duffel sits in front of 300+ airlines and returns offers you can actually
sell, which makes it the only source here whose numbers are quotable without
a hedge. Test-mode tokens work against a sandbox airline; production tokens
return real inventory.

Contract (API v2, verified Aug 2026):
  POST https://api.duffel.com/air/offer_requests?return_offers=true
  Headers: Authorization: Bearer <token>, Duffel-Version: v2
  Body:   {"data": {"slices": [...], "passengers": [...], "cabin_class": ...}}
"""

from __future__ import annotations

from typing import Any

from ..errors import ContractMismatch, NotConfigured
from ..models import FlightQuote, Freshness, Money, Segment, Slice
from ..query import FlightSearch
from ._util import parse_dt, parse_iso_duration, utcnow
from .base import Provider

BASE_URL = "https://api.duffel.com"
API_VERSION = "v2"

CABIN_MAP = {
    "economy": "economy",
    "premium_economy": "premium_economy",
    "business": "business",
    "first": "first",
}


class DuffelProvider(Provider):
    name = "duffel"
    supports_flights = True
    cacheable = False  # offers expire; never re-serve one from cache

    @property
    def configured(self) -> bool:
        return bool(self.config.duffel_token)

    @property
    def is_sandbox(self) -> bool:
        """Test tokens return invented inventory that looks entirely real."""
        return str(self.config.duffel_token or "").startswith("duffel_test")

    def setup_hint(self) -> str:
        return (
            "Set DUFFEL_ACCESS_TOKEN. Sign up at duffel.com — test tokens are "
            "free and immediate; production access needs a short review."
        )

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.config.duffel_token}",
            "Duffel-Version": API_VERSION,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    def _body(self, q: FlightSearch) -> dict[str, Any]:
        slices: list[dict[str, str]] = [
            {
                "origin": q.origin,
                "destination": q.destination,
                "departure_date": q.depart_date.isoformat(),
            }
        ]
        if q.return_date:
            slices.append(
                {
                    "origin": q.destination,
                    "destination": q.origin,
                    "departure_date": q.return_date.isoformat(),
                }
            )

        passengers: list[dict[str, str]] = [{"type": "adult"} for _ in range(q.adults)]
        passengers += [{"type": "child"} for _ in range(q.children)]
        passengers += [{"type": "infant_without_seat"} for _ in range(q.infants)]

        data: dict[str, Any] = {
            "slices": slices,
            "passengers": passengers,
            "cabin_class": CABIN_MAP[q.cabin],
        }
        if q.max_connections is not None:
            data["max_connections"] = q.max_connections
        return {"data": data}

    async def search_flights(self, query: FlightSearch, http) -> list[FlightQuote]:
        if not self.configured:
            raise NotConfigured(self.setup_hint())

        payload = await http.request_json(
            "POST",
            f"{BASE_URL}/air/offer_requests",
            headers=self._headers(),
            params={"return_offers": "true"},
            json=self._body(query),
        )

        if not isinstance(payload, dict) or "data" not in payload:
            raise ContractMismatch(
                "Duffel response had no 'data' key — the API contract may have moved"
            )

        offers = payload["data"].get("offers")
        if offers is None:
            raise ContractMismatch(
                "Duffel returned an offer request without inline offers; "
                "expected return_offers=true to populate 'data.offers'"
            )

        return [self._to_quote(o, query) for o in offers if isinstance(o, dict)]

    def _to_quote(self, offer: dict[str, Any], q: FlightSearch) -> FlightQuote:
        owner = offer.get("owner") or {}
        slices = [
            self._to_slice(s, owner_name=owner.get("name", ""))
            for s in offer.get("slices", [])
        ]

        seats = None
        for sl in offer.get("slices", []):
            for seg in sl.get("segments", []):
                for pax in seg.get("passengers", []) or []:
                    avail = pax.get("cabin", {}).get("amenities")
                    if isinstance(avail, dict) and "seats_remaining" in avail:
                        seats = avail["seats_remaining"]

        return FlightQuote(
            provider=self.name,
            price=Money.of(offer.get("total_amount", 0), offer.get("total_currency", q.currency)),
            slices=slices,
            freshness=Freshness.LIVE,
            bookable=True,
            sandbox=self.is_sandbox,
            observed_at=utcnow(),
            expires_at=parse_dt(offer.get("expires_at")),
            offer_id=offer.get("id"),
            cabin=q.cabin,
            baggage=_baggage_summary(offer),
            seats_remaining=seats,
            deep_link=None,
            raw=offer,
        )

    def _to_slice(self, sl: dict[str, Any], owner_name: str = "") -> Slice:
        segments = [
            Segment(
                origin=(seg.get("origin") or {}).get("iata_code", ""),
                destination=(seg.get("destination") or {}).get("iata_code", ""),
                depart=parse_dt(seg.get("departing_at")),  # type: ignore[arg-type]
                arrive=parse_dt(seg.get("arriving_at")),
                carrier=(seg.get("marketing_carrier") or {}).get("iata_code", ""),
                carrier_name=(seg.get("marketing_carrier") or {}).get("name", "")
                or owner_name,
                flight_number=str(seg.get("marketing_carrier_flight_number") or ""),
                aircraft=(seg.get("aircraft") or {}).get("name"),
                duration_minutes=parse_iso_duration(seg.get("duration")),
            )
            for seg in sl.get("segments", [])
        ]
        return Slice(
            origin=(sl.get("origin") or {}).get("iata_code", ""),
            destination=(sl.get("destination") or {}).get("iata_code", ""),
            segments=segments,
            duration_minutes=parse_iso_duration(sl.get("duration")),
        )

    async def probe(self, http) -> str:
        if not self.configured:
            raise NotConfigured(self.setup_hint())
        payload = await http.request_json(
            "GET",
            f"{BASE_URL}/air/airlines",
            headers=self._headers(),
            params={"limit": "1"},
        )
        count = len((payload or {}).get("data", []))
        if self.is_sandbox:
            return (
                f"authenticated (test mode, airlines endpoint returned {count} row) "
                "— returns invented fares; not real prices"
            )
        return f"authenticated (live mode, airlines endpoint returned {count} row)"


def _baggage_summary(offer: dict[str, Any]) -> str | None:
    """Checked-bag allowance, which is where headline fares mislead most."""
    for sl in offer.get("slices", []):
        for seg in sl.get("segments", []):
            for pax in seg.get("passengers", []) or []:
                for bag in pax.get("baggages", []) or []:
                    if bag.get("type") == "checked":
                        qty = bag.get("quantity", 0)
                        return f"{qty} checked bag" + ("s" if qty != 1 else "")
    return None
