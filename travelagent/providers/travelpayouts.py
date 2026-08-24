"""Travelpayouts / Aviasales flight data — cached real fares and price trends.

These are fares real people actually saw, served from a cache. They are not
bookable and may already be gone, so everything here is CACHED, never LIVE.
Two jobs it does better than a live API:

  * it answers "is this route normally 90 EUR or 400 EUR?" instantly, and
  * the month matrix answers "which date is cheapest?" in one call, which is
    usually where the real saving is.

Contract (verified Aug 2026):
  GET https://api.travelpayouts.com/v1/prices/cheap
  GET https://api.travelpayouts.com/v1/prices/month-matrix
  Auth: token query param, or X-Access-Token header.
"""

from __future__ import annotations

from datetime import date
from typing import Any

from ..errors import ContractMismatch, NotConfigured
from ..models import FlightQuote, Freshness, Money, Segment, Slice
from ..query import FlightSearch
from ._util import parse_dt, utcnow
from .base import Provider

BASE_URL = "https://api.travelpayouts.com"


class TravelpayoutsProvider(Provider):
    name = "travelpayouts"
    supports_flights = True

    @property
    def configured(self) -> bool:
        return bool(self.config.travelpayouts_token)

    def setup_hint(self) -> str:
        return (
            "Set TRAVELPAYOUTS_TOKEN (free affiliate signup at travelpayouts.com). "
            "Optionally set TRAVELPAYOUTS_MARKER for working booking deep links."
        )

    def _headers(self) -> dict[str, str]:
        return {"X-Access-Token": self.config.travelpayouts_token or ""}

    async def search_flights(self, query: FlightSearch, http) -> list[FlightQuote]:
        if not self.configured:
            raise NotConfigured(self.setup_hint())

        params: dict[str, Any] = {
            "origin": query.origin,
            "destination": query.destination,
            "depart_date": query.depart_date.isoformat(),
            "currency": query.currency.lower(),
            "one_way": "true" if query.one_way else "false",
        }
        if query.return_date:
            params["return_date"] = query.return_date.isoformat()

        payload = await http.request_json(
            "GET", f"{BASE_URL}/v1/prices/cheap", headers=self._headers(), params=params
        )
        return self._parse_cheap(payload, query)

    def _parse_cheap(self, payload: Any, query: FlightSearch) -> list[FlightQuote]:
        if not isinstance(payload, dict):
            raise ContractMismatch("Travelpayouts returned a non-object response")
        if payload.get("success") is False:
            raise ContractMismatch(f"Travelpayouts error: {payload.get('error')}")

        data = payload.get("data")
        if not isinstance(data, dict):
            return []

        quotes: list[FlightQuote] = []
        for dest, entries in data.items():
            if not isinstance(entries, dict):
                continue
            for entry in entries.values():
                if not isinstance(entry, dict) or "price" not in entry:
                    continue
                quotes.append(self._to_quote(entry, dest, query))
        return quotes

    def _to_quote(self, entry: dict[str, Any], dest: str, q: FlightSearch) -> FlightQuote:
        depart = parse_dt(entry.get("departure_at"))
        ret = parse_dt(entry.get("return_at"))
        carrier = str(entry.get("airline") or "")
        number = str(entry.get("flight_number") or "")

        slices = [
            Slice(
                is_summary=True,
                origin=q.origin,
                destination=dest,
                segments=[
                    Segment(
                        origin=q.origin,
                        destination=dest,
                        depart=depart,  # type: ignore[arg-type]
                        carrier=carrier,
                        flight_number=number,
                    )
                ]
                if depart
                else [],
            )
        ]
        if ret:
            slices.append(
                Slice(
                    is_summary=True,
                    origin=dest,
                    destination=q.origin,
                    segments=[
                        Segment(
                            origin=dest,
                            destination=q.origin,
                            depart=ret,
                            carrier=carrier,
                        )
                    ],
                )
            )

        return FlightQuote(
            provider=self.name,
            price=Money.of(entry["price"], q.currency),
            slices=slices,
            freshness=Freshness.CACHED,
            bookable=False,
            observed_at=utcnow(),
            expires_at=parse_dt(entry.get("expires_at")),
            deep_link=self._deep_link(q, dest, depart, ret),
            cabin=q.cabin,
            raw=entry,
        )

    async def price_calendar(
        self, query: FlightSearch, http, month: date | None = None
    ) -> dict[str, Money]:
        """Cheapest fare per departure date across a month.

        This is the "fly on the 14th instead and save 200" tool.
        """
        if not self.configured:
            raise NotConfigured(self.setup_hint())

        target = (month or query.depart_date).replace(day=1)
        payload = await http.request_json(
            "GET",
            f"{BASE_URL}/v1/prices/month-matrix",
            headers=self._headers(),
            params={
                "origin": query.origin,
                "destination": query.destination,
                "month": target.isoformat(),
                "currency": query.currency.lower(),
                "show_to_affiliates": "true",
            },
        )
        if not isinstance(payload, dict):
            raise ContractMismatch("Travelpayouts month-matrix returned a non-object")

        rows = payload.get("data") or []
        calendar: dict[str, Money] = {}
        for row in rows:
            if not isinstance(row, dict):
                continue
            day = row.get("depart_date")
            price = row.get("value") if "value" in row else row.get("price")
            if not day or price is None:
                continue
            day = str(day)[:10]
            money = Money.of(price, query.currency)
            if day not in calendar or money.amount < calendar[day].amount:
                calendar[day] = money
        return dict(sorted(calendar.items()))

    def _deep_link(
        self, q: FlightSearch, dest: str, depart, ret
    ) -> str | None:
        """Aviasales search URL: ORIGIN + DDMM + DEST + DDMM + passengers."""
        d = (depart.date() if depart else q.depart_date).strftime("%d%m")
        path = f"{q.origin}{d}{dest}"
        if ret or q.return_date:
            r = (ret.date() if ret else q.return_date).strftime("%d%m")  # type: ignore[union-attr]
            path += r
        path += str(max(q.adults, 1))
        url = f"https://www.aviasales.com/search/{path}"
        if self.config.travelpayouts_marker:
            url += f"?marker={self.config.travelpayouts_marker}"
        return url

    async def probe(self, http) -> str:
        if not self.configured:
            raise NotConfigured(self.setup_hint())
        payload = await http.request_json(
            "GET",
            f"{BASE_URL}/v1/prices/cheap",
            headers=self._headers(),
            params={"origin": "LON", "destination": "PAR", "currency": "eur"},
        )
        if not isinstance(payload, dict) or payload.get("success") is False:
            raise ContractMismatch(f"probe rejected: {payload}")
        n = sum(len(v) for v in (payload.get("data") or {}).values() if isinstance(v, dict))
        return f"authenticated (LON->PAR probe returned {n} cached fares)"
