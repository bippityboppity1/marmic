"""Serialization for cached quotes.

Only CACHED-freshness quotes are ever stored (see `Provider.cacheable`), so
round-tripping never resurrects a live offer as though it were still live.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from typing import Any

from .models import FlightQuote, Freshness, HotelQuote, Money, Segment, Slice


def _dt(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def _undt(value: str | None) -> datetime | None:
    return datetime.fromisoformat(value) if value else None


def _undate(value: str | None) -> date | None:
    return date.fromisoformat(value) if value else None


def money_to_dict(m: Money) -> dict[str, Any]:
    return {"amount": str(m.amount), "currency": m.currency}


def money_from_dict(d: dict[str, Any]) -> Money:
    return Money(Decimal(d["amount"]), d["currency"])


def flight_to_dict(q: FlightQuote) -> dict[str, Any]:
    return {
        "provider": q.provider,
        "price": money_to_dict(q.price),
        "freshness": q.freshness.value,
        "bookable": q.bookable,
        "observed_at": _dt(q.observed_at),
        "expires_at": _dt(q.expires_at),
        "deep_link": q.deep_link,
        "offer_id": q.offer_id,
        "cabin": q.cabin,
        "baggage": q.baggage,
        "seats_remaining": q.seats_remaining,
        "slices": [
            {
                "origin": s.origin,
                "destination": s.destination,
                "duration_minutes": s.duration_minutes,
                "stop_count": s.stop_count,
                "is_summary": s.is_summary,
                "segments": [
                    {
                        "origin": seg.origin,
                        "destination": seg.destination,
                        "depart": _dt(seg.depart),
                        "arrive": _dt(seg.arrive),
                        "carrier": seg.carrier,
                        "carrier_name": seg.carrier_name,
                        "flight_number": seg.flight_number,
                        "aircraft": seg.aircraft,
                        "duration_minutes": seg.duration_minutes,
                    }
                    for seg in s.segments
                ],
            }
            for s in q.slices
        ],
    }


def flight_from_dict(d: dict[str, Any]) -> FlightQuote:
    return FlightQuote(
        provider=d["provider"],
        price=money_from_dict(d["price"]),
        slices=[
            Slice(
                origin=s["origin"],
                destination=s["destination"],
                duration_minutes=s.get("duration_minutes"),
                stop_count=s.get("stop_count"),
                is_summary=s.get("is_summary", False),
                segments=[
                    Segment(
                        origin=seg["origin"],
                        destination=seg["destination"],
                        depart=_undt(seg.get("depart")),  # type: ignore[arg-type]
                        arrive=_undt(seg.get("arrive")),
                        carrier=seg.get("carrier", ""),
                        carrier_name=seg.get("carrier_name", ""),
                        flight_number=seg.get("flight_number", ""),
                        aircraft=seg.get("aircraft"),
                        duration_minutes=seg.get("duration_minutes"),
                    )
                    for seg in s.get("segments", [])
                ],
            )
            for s in d.get("slices", [])
        ],
        freshness=Freshness(d.get("freshness", "cached")),
        bookable=d.get("bookable", False),
        observed_at=_undt(d.get("observed_at")),  # type: ignore[arg-type]
        expires_at=_undt(d.get("expires_at")),
        deep_link=d.get("deep_link"),
        offer_id=d.get("offer_id"),
        cabin=d.get("cabin"),
        baggage=d.get("baggage"),
        seats_remaining=d.get("seats_remaining"),
    )


def hotel_to_dict(q: HotelQuote) -> dict[str, Any]:
    return {
        "provider": q.provider,
        "name": q.name,
        "price_total": money_to_dict(q.price_total),
        "nights": q.nights,
        "freshness": q.freshness.value,
        "bookable": q.bookable,
        "stars": q.stars,
        "rating": q.rating,
        "reviews": q.reviews,
        "neighborhood": q.neighborhood,
        "distance_km_center": q.distance_km_center,
        "check_in": q.check_in.isoformat() if q.check_in else None,
        "check_out": q.check_out.isoformat() if q.check_out else None,
        "room_type": q.room_type,
        "deep_link": q.deep_link,
        "observed_at": _dt(q.observed_at),
    }


def hotel_from_dict(d: dict[str, Any]) -> HotelQuote:
    return HotelQuote(
        provider=d["provider"],
        name=d["name"],
        price_total=money_from_dict(d["price_total"]),
        nights=d.get("nights", 1),
        freshness=Freshness(d.get("freshness", "cached")),
        bookable=d.get("bookable", False),
        stars=d.get("stars"),
        rating=d.get("rating"),
        reviews=d.get("reviews"),
        neighborhood=d.get("neighborhood"),
        distance_km_center=d.get("distance_km_center"),
        check_in=_undate(d.get("check_in")),
        check_out=_undate(d.get("check_out")),
        room_type=d.get("room_type"),
        deep_link=d.get("deep_link"),
        observed_at=_undt(d.get("observed_at")),  # type: ignore[arg-type]
    )
