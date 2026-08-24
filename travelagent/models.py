"""Normalized price types shared by every provider.

The whole point of this module is `Freshness`. A travel agent's credibility
rests on knowing which numbers you can hold someone to and which are weather
reports. Every quote carries its provenance so downstream planning never has
to guess.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any


class Freshness(enum.Enum):
    """How much weight a price can bear."""

    LIVE = "live"
    """Fetched from live inventory this second. Bookable at this price now."""

    CACHED = "cached"
    """A real fare a real person saw recently, served from the provider's
    cache. Directionally true, may already be gone."""

    ESTIMATE = "estimate"
    """Historical or modelled. A planning range, never a quote."""

    @property
    def is_quotable(self) -> bool:
        """True if this number may be presented as an actual price."""
        return self is Freshness.LIVE

    @property
    def label(self) -> str:
        return {
            Freshness.LIVE: "live",
            Freshness.CACHED: "cached",
            Freshness.ESTIMATE: "est.",
        }[self]


@dataclass(frozen=True, order=True)
class Money:
    amount: Decimal
    currency: str

    def __post_init__(self) -> None:
        if not isinstance(self.amount, Decimal):
            object.__setattr__(self, "amount", Decimal(str(self.amount)))
        object.__setattr__(self, "currency", self.currency.upper())

    def __str__(self) -> str:
        return f"{self.amount:,.0f} {self.currency}"

    @classmethod
    def of(cls, amount: Any, currency: str) -> Money:
        return cls(Decimal(str(amount)), currency)


@dataclass
class Segment:
    """One aircraft, gate to gate."""

    origin: str
    destination: str
    depart: datetime
    arrive: datetime | None = None
    carrier: str = ""
    carrier_name: str = ""
    flight_number: str = ""
    aircraft: str | None = None
    duration_minutes: int | None = None

    @property
    def designator(self) -> str:
        return f"{self.carrier}{self.flight_number}".strip()


@dataclass
class Slice:
    """One direction of travel: outbound or return."""

    origin: str
    destination: str
    segments: list[Segment] = field(default_factory=list)
    duration_minutes: int | None = None
    stop_count: int | None = None
    """Set only when a source reports the number of stops without naming the
    legs. Fabricating placeholder segments instead would invent connection
    times that were never in the data."""
    is_summary: bool = False
    """True when the segments summarise a journey rather than enumerate it.

    Some sources return one row per itinerary with no leg breakdown. There a
    single segment means "we were not told", not "nonstop" — and rendering it
    as nonstop would be inventing a fact about the booking.
    """

    @property
    def stops(self) -> int | None:
        """Stop count, or None when the source never told us."""
        if self.stop_count is not None:
            return self.stop_count
        if self.is_summary:
            return None
        return max(len(self.segments) - 1, 0)

    @property
    def depart(self) -> datetime | None:
        return self.segments[0].depart if self.segments else None

    @property
    def arrive(self) -> datetime | None:
        return self.segments[-1].arrive if self.segments else None

    @property
    def carriers(self) -> list[str]:
        seen: list[str] = []
        for seg in self.segments:
            if seg.carrier and seg.carrier not in seen:
                seen.append(seg.carrier)
        return seen

    @property
    def connection_minutes(self) -> list[int]:
        """Layover durations, in order. The thing that actually breaks trips."""
        gaps: list[int] = []
        for prev, nxt in zip(self.segments, self.segments[1:], strict=False):
            if prev.arrive and nxt.depart:
                gaps.append(int((nxt.depart - prev.arrive).total_seconds() // 60))
        return gaps


@dataclass
class FlightQuote:
    provider: str
    price: Money
    slices: list[Slice] = field(default_factory=list)
    freshness: Freshness = Freshness.CACHED
    bookable: bool = False
    """True only when the provider can actually sell this offer."""
    observed_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    expires_at: datetime | None = None
    deep_link: str | None = None
    offer_id: str | None = None
    cabin: str | None = None
    baggage: str | None = None
    seats_remaining: int | None = None
    raw: dict[str, Any] = field(default_factory=dict, repr=False)

    @property
    def stops(self) -> int | None:
        known = [s.stops for s in self.slices if s.stops is not None]
        return max(known) if known else None

    @property
    def carriers(self) -> list[str]:
        seen: list[str] = []
        for sl in self.slices:
            for c in sl.carriers:
                if c not in seen:
                    seen.append(c)
        return seen

    @property
    def total_duration_minutes(self) -> int | None:
        mins = [s.duration_minutes for s in self.slices if s.duration_minutes]
        return sum(mins) if mins else None

    @property
    def is_self_transfer(self) -> bool:
        """Separate tickets have no protection when leg one slips.

        The trip-planning skill calls this out every time, so surface it as
        structured data rather than leaving it to prose.
        """
        return len(self.carriers) > 1 and not self.bookable

    @property
    def shortest_connection_minutes(self) -> int | None:
        gaps = [g for sl in self.slices for g in sl.connection_minutes]
        return min(gaps) if gaps else None

    def signature(self) -> tuple:
        """Identity for dedupe across providers: same metal, same times."""
        legs = []
        for sl in self.slices:
            for seg in sl.segments:
                legs.append(
                    (
                        seg.designator,
                        seg.origin,
                        seg.destination,
                        seg.depart.replace(tzinfo=None).isoformat(timespec="minutes")
                        if seg.depart
                        else "",
                    )
                )
        return tuple(legs)


@dataclass
class HotelQuote:
    provider: str
    name: str
    price_total: Money
    nights: int
    freshness: Freshness = Freshness.CACHED
    bookable: bool = False
    price_per_night: Money | None = None
    stars: float | None = None
    rating: float | None = None
    reviews: int | None = None
    neighborhood: str | None = None
    distance_km_center: float | None = None
    check_in: date | None = None
    check_out: date | None = None
    room_type: str | None = None
    deep_link: str | None = None
    observed_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    raw: dict[str, Any] = field(default_factory=dict, repr=False)

    def __post_init__(self) -> None:
        if self.price_per_night is None and self.nights > 0:
            self.price_per_night = Money(
                self.price_total.amount / Decimal(self.nights),
                self.price_total.currency,
            )

    def signature(self) -> tuple:
        return (self.name.strip().casefold(), self.check_in, self.check_out)


@dataclass
class ProviderError:
    provider: str
    message: str
    kind: str = "error"
    """One of: error, unconfigured, timeout, rate_limited, blocked."""

    def __str__(self) -> str:
        return f"{self.provider}: {self.message}"


@dataclass
class SearchResult:
    """What a fan-out returns: the quotes plus an honest account of failures."""

    flights: list[FlightQuote] = field(default_factory=list)
    hotels: list[HotelQuote] = field(default_factory=list)
    errors: list[ProviderError] = field(default_factory=list)
    providers_queried: list[str] = field(default_factory=list)
    query: dict[str, Any] = field(default_factory=dict)

    @property
    def providers_succeeded(self) -> list[str]:
        failed = {e.provider for e in self.errors}
        return [p for p in self.providers_queried if p not in failed]

    @property
    def has_live(self) -> bool:
        return any(q.freshness is Freshness.LIVE for q in self.flights + self.hotels)  # type: ignore[operator]

    def cheapest_flight(self) -> FlightQuote | None:
        return min(self.flights, key=lambda q: q.price.amount, default=None)

    def cheapest_bookable_flight(self) -> FlightQuote | None:
        live = [q for q in self.flights if q.bookable]
        return min(live, key=lambda q: q.price.amount, default=None)
