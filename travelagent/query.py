"""Search inputs, validated once so providers can trust them."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta

from .errors import TravelAgentError

IATA = re.compile(r"^[A-Z]{3}$")
CABINS = {"economy", "premium_economy", "business", "first"}


def _parse_date(value: date | str) -> date:
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise TravelAgentError(f"invalid date {value!r}, expected YYYY-MM-DD") from exc


@dataclass
class FlightSearch:
    origin: str
    destination: str
    depart_date: date
    return_date: date | None = None
    adults: int = 1
    children: int = 0
    infants: int = 0
    cabin: str = "economy"
    max_connections: int | None = None
    currency: str = "EUR"
    date_flexibility_days: int = 0
    """Widen the search by +/- N days. Frequently the real saving."""

    def __post_init__(self) -> None:
        self.origin = self.origin.strip().upper()
        self.destination = self.destination.strip().upper()
        self.depart_date = _parse_date(self.depart_date)
        if self.return_date:
            self.return_date = _parse_date(self.return_date)
        self.cabin = self.cabin.strip().lower()
        self.currency = self.currency.strip().upper()

        for code, label in ((self.origin, "origin"), (self.destination, "destination")):
            if not IATA.match(code):
                raise TravelAgentError(
                    f"{label} {code!r} is not a 3-letter IATA code"
                )
        if self.origin == self.destination:
            raise TravelAgentError("origin and destination are the same airport")
        if self.cabin not in CABINS:
            raise TravelAgentError(
                f"cabin {self.cabin!r} must be one of {sorted(CABINS)}"
            )
        if self.return_date and self.return_date < self.depart_date:
            raise TravelAgentError("return_date is before depart_date")
        if self.adults < 1:
            raise TravelAgentError("need at least one adult passenger")

    @property
    def one_way(self) -> bool:
        return self.return_date is None

    @property
    def passenger_count(self) -> int:
        return self.adults + self.children + self.infants

    def date_window(self) -> list[date]:
        """Departure dates to probe when flexibility is requested."""
        n = self.date_flexibility_days
        if n <= 0:
            return [self.depart_date]
        return [
            self.depart_date + timedelta(days=d)
            for d in range(-n, n + 1)
            if (self.depart_date + timedelta(days=d)) >= date.today()
        ]

    def shifted(self, days: int) -> FlightSearch:
        return FlightSearch(
            origin=self.origin,
            destination=self.destination,
            depart_date=self.depart_date + timedelta(days=days),
            return_date=self.return_date + timedelta(days=days)
            if self.return_date
            else None,
            adults=self.adults,
            children=self.children,
            infants=self.infants,
            cabin=self.cabin,
            max_connections=self.max_connections,
            currency=self.currency,
        )

    def cache_payload(self) -> dict:
        return {
            "origin": self.origin,
            "destination": self.destination,
            "depart": self.depart_date.isoformat(),
            "return": self.return_date.isoformat() if self.return_date else None,
            "adults": self.adults,
            "children": self.children,
            "infants": self.infants,
            "cabin": self.cabin,
            "currency": self.currency,
            "max_connections": self.max_connections,
        }


@dataclass
class HotelSearch:
    location: str
    check_in: date
    check_out: date
    adults: int = 2
    rooms: int = 1
    currency: str = "EUR"
    limit: int = 20
    max_price_per_night: float | None = None
    min_stars: float | None = None

    def __post_init__(self) -> None:
        self.location = self.location.strip()
        self.check_in = _parse_date(self.check_in)
        self.check_out = _parse_date(self.check_out)
        self.currency = self.currency.strip().upper()
        if not self.location:
            raise TravelAgentError("location is required")
        if self.check_out <= self.check_in:
            raise TravelAgentError("check_out must be after check_in")
        if self.rooms < 1 or self.adults < 1:
            raise TravelAgentError("need at least one adult and one room")

    @property
    def nights(self) -> int:
        return (self.check_out - self.check_in).days

    def cache_payload(self) -> dict:
        return {
            "location": self.location.casefold(),
            "check_in": self.check_in.isoformat(),
            "check_out": self.check_out.isoformat(),
            "adults": self.adults,
            "rooms": self.rooms,
            "currency": self.currency,
            "limit": self.limit,
        }
