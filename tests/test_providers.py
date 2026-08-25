"""Provider adapters, pinned against recorded API responses."""

from __future__ import annotations

from dataclasses import replace
from datetime import date, datetime
from decimal import Decimal

import pytest

from travelagent.errors import Blocked, ContractMismatch, NotConfigured
from travelagent.models import Freshness
from travelagent.providers.duffel import DuffelProvider
from travelagent.providers.hotellook import HotellookProvider
from travelagent.providers.travelpayouts import TravelpayoutsProvider
from travelagent.query import FlightSearch, HotelSearch

from .conftest import FailingHttp, FakeHttp, load_json


@pytest.fixture
def flight_query():
    return FlightSearch(origin="NAP", destination="LHR", depart_date="2026-10-15")


@pytest.fixture
def hotel_query():
    return HotelSearch(location="Naples", check_in="2026-10-15", check_out="2026-10-18")


# --- Duffel ---------------------------------------------------------------


async def test_duffel_parses_offers(config, flight_query):
    http = FakeHttp(load_json("duffel_offers.json"))
    quotes = await DuffelProvider(config).search_flights(flight_query, http)

    assert len(quotes) == 2
    nonstop, connecting = quotes

    assert nonstop.price.amount == Decimal("184.32")
    assert nonstop.price.currency == "EUR"
    assert nonstop.stops == 0
    assert nonstop.carriers == ["BA"]
    assert nonstop.baggage == "1 checked bag"
    assert nonstop.seats_remaining == 4
    assert nonstop.slices[0].duration_minutes == 185
    assert nonstop.slices[0].segments[0].designator == "BA2611"

    assert connecting.stops == 1
    assert connecting.slices[0].duration_minutes == 400


async def test_duffel_test_token_marks_offers_as_sandbox(config, flight_query):
    """The `config` fixture carries a duffel_test_ token, as most setups do."""
    http = FakeHttp(load_json("duffel_offers.json"))
    quote = (await DuffelProvider(config).search_flights(flight_query, http))[0]

    assert quote.sandbox is True
    # Duffel really would sell this — inside its sandbox, for an airline that
    # does not exist. The raw fact stays true; what changes is whether anyone
    # may repeat the number.
    assert quote.bookable is True
    assert quote.quotable is False


async def test_duffel_live_token_offers_are_quotable(config, flight_query):
    live = replace(config, duffel_token="duffel_live_xyz789")
    http = FakeHttp(load_json("duffel_offers.json"))
    quote = (await DuffelProvider(live).search_flights(flight_query, http))[0]

    assert quote.sandbox is False
    assert quote.quotable is True


async def test_duffel_offers_are_live_and_bookable(config, flight_query):
    http = FakeHttp(load_json("duffel_offers.json"))
    quote = (await DuffelProvider(config).search_flights(flight_query, http))[0]

    assert quote.freshness is Freshness.LIVE
    assert quote.bookable is True
    assert quote.freshness.is_quotable is True
    assert quote.expires_at == datetime.fromisoformat("2026-08-24T16:30:00+00:00")


async def test_duffel_computes_connection_time(config, flight_query):
    """The 35-minute connection is the whole reason this field exists."""
    http = FakeHttp(load_json("duffel_offers.json"))
    connecting = (await DuffelProvider(config).search_flights(flight_query, http))[1]

    assert connecting.slices[0].connection_minutes == [35]
    assert connecting.shortest_connection_minutes == 35


async def test_duffel_sends_the_documented_contract(config, flight_query):
    http = FakeHttp(load_json("duffel_offers.json"))
    await DuffelProvider(config).search_flights(flight_query, http)

    call = http.calls[0]
    assert call["method"] == "POST"
    assert call["url"].endswith("/air/offer_requests")
    assert call["headers"]["Duffel-Version"] == "v2"
    assert call["headers"]["Authorization"].startswith("Bearer ")
    assert call["params"] == {"return_offers": "true"}
    assert call["json"]["data"]["slices"] == [
        {"origin": "NAP", "destination": "LHR", "departure_date": "2026-10-15"}
    ]
    assert call["json"]["data"]["passengers"] == [{"type": "adult"}]


async def test_duffel_return_trip_sends_two_slices(config):
    query = FlightSearch(
        origin="NAP",
        destination="LHR",
        depart_date="2026-10-15",
        return_date="2026-10-22",
        adults=2,
        children=1,
    )
    http = FakeHttp(load_json("duffel_offers.json"))
    await DuffelProvider(config).search_flights(query, http)

    data = http.calls[0]["json"]["data"]
    assert [s["origin"] for s in data["slices"]] == ["NAP", "LHR"]
    assert data["slices"][1]["departure_date"] == "2026-10-22"
    assert [p["type"] for p in data["passengers"]] == ["adult", "adult", "child"]


async def test_duffel_raises_on_moved_contract(config, flight_query):
    http = FakeHttp({"data": {"id": "orq_1"}})  # no inline offers
    with pytest.raises(ContractMismatch, match="inline offers"):
        await DuffelProvider(config).search_flights(flight_query, http)


async def test_duffel_without_token_is_unconfigured(config, flight_query):
    config.duffel_token = None
    provider = DuffelProvider(config)
    assert provider.configured is False
    with pytest.raises(NotConfigured):
        await provider.search_flights(flight_query, FakeHttp())


# --- Travelpayouts --------------------------------------------------------


async def test_travelpayouts_parses_cached_fares(config, flight_query):
    http = FakeHttp(load_json("travelpayouts_cheap.json"))
    quotes = await TravelpayoutsProvider(config).search_flights(flight_query, http)

    assert len(quotes) == 2
    cheap = min(quotes, key=lambda q: q.price.amount)
    assert cheap.price.amount == Decimal("96")
    assert cheap.carriers == ["FR"]


async def test_travelpayouts_is_never_quotable(config, flight_query):
    """These are sightings, not offers. The flag downstream depends on it."""
    http = FakeHttp(load_json("travelpayouts_cheap.json"))
    quotes = await TravelpayoutsProvider(config).search_flights(flight_query, http)

    assert all(q.freshness is Freshness.CACHED for q in quotes)
    assert all(q.bookable is False for q in quotes)
    assert all(q.freshness.is_quotable is False for q in quotes)


async def test_travelpayouts_builds_a_deep_link(config, flight_query):
    http = FakeHttp(load_json("travelpayouts_cheap.json"))
    quote = (await TravelpayoutsProvider(config).search_flights(flight_query, http))[0]

    assert quote.deep_link is not None
    assert "aviasales.com/search/NAP1510LHR" in quote.deep_link
    assert "marker=12345" in quote.deep_link


async def test_travelpayouts_surfaces_api_level_errors(config, flight_query):
    http = FakeHttp({"success": False, "error": "invalid token", "data": {}})
    with pytest.raises(ContractMismatch, match="invalid token"):
        await TravelpayoutsProvider(config).search_flights(flight_query, http)


async def test_price_calendar_keeps_the_cheapest_per_day(config, flight_query):
    """The fixture has two entries for the 15th; the cheaper one must win."""
    http = FakeHttp(load_json("travelpayouts_month_matrix.json"))
    calendar = await TravelpayoutsProvider(config).price_calendar(
        flight_query, http, month=date(2026, 10, 1)
    )

    assert calendar["2026-10-15"].amount == Decimal("176")
    assert min(calendar, key=lambda d: calendar[d].amount) == "2026-10-16"
    assert list(calendar) == sorted(calendar)


# --- Hotellook ------------------------------------------------------------


async def test_hotellook_parses_and_derives_per_night(config, hotel_query):
    http = FakeHttp(load_json("hotellook_cache.json"))
    quotes = await HotellookProvider(config).search_hotels(hotel_query, http)

    assert len(quotes) == 3
    hostel = next(q for q in quotes if q.name == "Ostello Vergini")
    assert hostel.price_total.amount == Decimal("94.0")
    assert hostel.nights == 3
    assert hostel.price_per_night is not None
    assert round(float(hostel.price_per_night.amount), 2) == 31.33
    assert hostel.freshness is Freshness.CACHED


async def test_hotellook_applies_filters(config):
    query = HotelSearch(
        location="Naples",
        check_in="2026-10-15",
        check_out="2026-10-18",
        max_price_per_night=100,
        min_stars=3,
    )
    http = FakeHttp(load_json("hotellook_cache.json"))
    quotes = await HotellookProvider(config).search_hotels(query, http)

    # 4-star at 268/3 = 89.50/night passes both; hostel fails stars; 5-star fails price.
    assert [q.name for q in quotes] == ["Hotel Piazza Bellini"]


async def test_hotellook_rejects_a_changed_shape(config, hotel_query):
    http = FakeHttp({"unexpected": "object"})
    with pytest.raises(ContractMismatch, match="non-list"):
        await HotellookProvider(config).search_hotels(hotel_query, http)


async def test_transport_failures_propagate(config, hotel_query):
    http = FailingHttp(Blocked("egress blocked"))
    with pytest.raises(Blocked):
        await HotellookProvider(config).search_hotels(hotel_query, http)


async def test_travelpayouts_does_not_claim_a_stop_count(config, flight_query):
    """This API returns one summary row per itinerary and never says how many
    legs it has. Reporting that as 'nonstop' would invent a fact."""
    http = FakeHttp(load_json("travelpayouts_cheap.json"))
    quotes = await TravelpayoutsProvider(config).search_flights(flight_query, http)

    assert all(q.stops is None for q in quotes)
    assert all(sl.is_summary for q in quotes for sl in q.slices)


async def test_duffel_stop_counts_are_known_because_legs_are_enumerated(config, flight_query):
    http = FakeHttp(load_json("duffel_offers.json"))
    nonstop, connecting = await DuffelProvider(config).search_flights(flight_query, http)

    assert nonstop.stops == 0        # genuinely nonstop, not merely unreported
    assert connecting.stops == 1
