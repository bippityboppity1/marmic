"""Scraper extraction, tested as pure functions over saved pages.

No browser is launched here. Rendering is the part that breaks in the wild;
parsing is the part worth pinning, and keeping it a pure function is what
makes that possible.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from travelagent.models import Freshness
from travelagent.query import FlightSearch, HotelSearch
from travelagent.scrapers._shared import parse_amount, parse_clock, parse_money_text
from travelagent.scrapers.booking import build_url as booking_url
from travelagent.scrapers.booking import parse_hotels
from travelagent.scrapers.google_flights import build_url as gf_url
from travelagent.scrapers.google_flights import parse_flights

from .conftest import load_text


@pytest.fixture
def flight_query():
    return FlightSearch(origin="NAP", destination="LHR", depart_date="2026-10-15")


@pytest.fixture
def hotel_query():
    return HotelSearch(location="Naples", check_in="2026-10-15", check_out="2026-10-18")


# --- shared text parsing --------------------------------------------------


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("184", 184.0),
        ("1,240", 1240.0),      # en-GB thousands
        ("1.940", 1940.0),      # it-IT thousands
        ("1,234.56", 1234.56),  # en-GB decimal
        ("1.234,56", 1234.56),  # it-IT decimal
        ("8.7", 8.7),           # two decimals, not thousands
        ("94,5", 94.5),
        ("", None),
        ("n/a", None),
    ],
)
def test_parse_amount_handles_both_locales(raw, expected):
    assert parse_amount(raw) == expected


@pytest.mark.parametrize(
    "raw, expected",
    [("7:15 AM", (7, 15)), ("12:40 PM", (12, 40)), ("12:05 AM", (0, 5)), ("19:30", (19, 30))],
)
def test_parse_clock(raw, expected):
    t = parse_clock(raw)
    assert (t.hour, t.minute) == expected


def test_parse_clock_rejects_nonsense():
    assert parse_clock("not a time") is None
    assert parse_clock("99:99") is None


def test_parse_money_text():
    assert parse_money_text("€268") == (268.0, "EUR")
    assert parse_money_text("Sold out") is None


# --- Google Flights -------------------------------------------------------


def test_google_flights_reads_accessibility_labels(flight_query):
    quotes = parse_flights(load_text("google_flights.html"), flight_query)

    assert len(quotes) == 3
    by_price = {q.price.amount: q for q in quotes}
    assert set(by_price) == {Decimal("184"), Decimal("1240"), Decimal("96")}

    nonstop = by_price[Decimal("184")]
    assert nonstop.stops == 0
    assert nonstop.slices[0].segments[0].carrier_name == "British Airways"
    assert nonstop.total_duration_minutes == 185
    assert nonstop.slices[0].depart.hour == 7
    assert nonstop.slices[0].depart.minute == 15
    assert nonstop.slices[0].arrive.hour == 9


def test_google_flights_carries_stops_without_inventing_legs(flight_query):
    """A stop count is not a licence to fabricate connection times."""
    quotes = parse_flights(load_text("google_flights.html"), flight_query)
    two_stop = next(q for q in quotes if q.price.amount == Decimal("96"))

    assert two_stop.stops == 2
    assert len(two_stop.slices[0].segments) == 1
    assert two_stop.slices[0].connection_minutes == []
    assert two_stop.shortest_connection_minutes is None


def test_google_flights_prices_are_live_but_not_bookable(flight_query):
    """We can read the price; we cannot sell the seat. Both matter."""
    quotes = parse_flights(load_text("google_flights.html"), flight_query)

    assert all(q.freshness is Freshness.LIVE for q in quotes)
    assert all(q.bookable is False for q in quotes)


def test_google_flights_skips_rows_without_a_price(flight_query):
    quotes = parse_flights(load_text("google_flights.html"), flight_query)
    assert all(q.price.amount > 0 for q in quotes)


def test_google_flights_returns_nothing_for_an_unrelated_page(flight_query):
    assert parse_flights("<html><body>no results here</body></html>", flight_query) == []


def test_google_flights_url_carries_dates_and_currency(flight_query):
    url = gf_url(flight_query)
    assert "2026-10-15" in url
    assert "curr=EUR" in url
    assert url.startswith("https://www.google.com/travel/flights?")


# --- Booking.com ----------------------------------------------------------


def test_booking_parses_property_cards(hotel_query):
    quotes = parse_hotels(load_text("booking_results.html"), hotel_query)

    assert [q.name for q in quotes] == [
        "Hotel Piazza Bellini",
        "Ostello Vergini",
        "Grand Hotel Vesuvio",
    ]
    first = quotes[0]
    assert first.price_total.amount == Decimal("268")
    assert first.price_total.currency == "EUR"
    assert first.rating == 8.7
    assert first.room_type == "Double Room"
    assert first.neighborhood == "Centro Storico, Naples"


def test_booking_reads_localised_thousands(hotel_query):
    """'€1.940' is 1940 euros in it-IT, not 1.94."""
    quotes = parse_hotels(load_text("booking_results.html"), hotel_query)
    grand = next(q for q in quotes if q.name == "Grand Hotel Vesuvio")
    assert grand.price_total.amount == Decimal("1940")


def test_booking_converts_distance_units(hotel_query):
    quotes = parse_hotels(load_text("booking_results.html"), hotel_query)
    assert quotes[0].distance_km_center == 0.8   # "800 m"
    assert quotes[1].distance_km_center == 1.6   # "1.6 km"


def test_booking_skips_cards_without_a_price(hotel_query):
    quotes = parse_hotels(load_text("booking_results.html"), hotel_query)
    assert "Sold Out Inn" not in [q.name for q in quotes]


def test_booking_derives_per_night_from_stay_length(hotel_query):
    quotes = parse_hotels(load_text("booking_results.html"), hotel_query)
    hostel = next(q for q in quotes if q.name == "Ostello Vergini")
    assert hostel.nights == 3
    assert round(float(hostel.price_per_night.amount), 2) == 31.33


def test_booking_url_carries_the_search(hotel_query):
    url = booking_url(hotel_query)
    assert "checkin=2026-10-15" in url
    assert "checkout=2026-10-18" in url
    assert "selected_currency=EUR" in url
