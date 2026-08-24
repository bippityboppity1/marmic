"""Input validation: reject nonsense before it reaches a paid API call."""

from __future__ import annotations

from datetime import date, timedelta

import pytest

from travelagent.errors import TravelAgentError
from travelagent.query import FlightSearch, HotelSearch


def test_normalises_codes_and_dates():
    q = FlightSearch(origin="nap", destination="lhr", depart_date="2026-10-15")
    assert (q.origin, q.destination) == ("NAP", "LHR")
    assert q.depart_date == date(2026, 10, 15)
    assert q.one_way is True


@pytest.mark.parametrize(
    "kwargs, message",
    [
        ({"origin": "NAPLES", "destination": "LHR"}, "IATA"),
        ({"origin": "NAP", "destination": "NAP"}, "same airport"),
        ({"origin": "NAP", "destination": "LHR", "cabin": "luxury"}, "cabin"),
        ({"origin": "NAP", "destination": "LHR", "adults": 0}, "adult"),
    ],
)
def test_rejects_bad_input(kwargs, message):
    kwargs.setdefault("depart_date", "2026-10-15")
    with pytest.raises(TravelAgentError, match=message):
        FlightSearch(**kwargs)


def test_rejects_return_before_departure():
    with pytest.raises(TravelAgentError, match="before depart_date"):
        FlightSearch(
            origin="NAP",
            destination="LHR",
            depart_date="2026-10-15",
            return_date="2026-10-01",
        )


def test_date_window_expands_and_excludes_the_past():
    q = FlightSearch(
        origin="NAP",
        destination="LHR",
        depart_date=date.today() + timedelta(days=1),
        date_flexibility_days=3,
    )
    window = q.date_window()
    assert len(window) == 5  # -1 clipped at today, so today..+4
    assert all(d >= date.today() for d in window)


def test_shifted_moves_both_legs_together():
    q = FlightSearch(
        origin="NAP",
        destination="LHR",
        depart_date="2026-10-15",
        return_date="2026-10-22",
    )
    moved = q.shifted(-2)
    assert moved.depart_date == date(2026, 10, 13)
    assert moved.return_date == date(2026, 10, 20)


def test_hotel_nights_and_validation():
    h = HotelSearch(location="Naples", check_in="2026-10-15", check_out="2026-10-18")
    assert h.nights == 3
    with pytest.raises(TravelAgentError, match="after check_in"):
        HotelSearch(location="Naples", check_in="2026-10-18", check_out="2026-10-15")
