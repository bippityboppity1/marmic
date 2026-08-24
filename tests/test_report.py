"""Rendering. Provenance must be visible in the table, not a footnote."""

from __future__ import annotations

from travelagent.models import Freshness, HotelQuote, Money, ProviderError, SearchResult
from travelagent.report import (
    render_calendar,
    render_flights,
    render_hotels,
    warnings,
)

from .test_search import make_quote


def result_with(*quotes, errors=None, providers=("duffel", "travelpayouts")):
    return SearchResult(
        flights=list(quotes),
        errors=list(errors or []),
        providers_queried=list(providers),
        query={"origin": "NAP", "destination": "LHR", "depart": "2026-10-15"},
    )


def test_bookable_and_cached_are_labelled_differently():
    live = make_quote(provider="duffel", price=184, bookable=True, freshness=Freshness.LIVE)
    cached = make_quote(provider="travelpayouts", price=96, designator="FR8391")

    out = render_flights(result_with(live, cached))
    assert "**bookable**" in out
    assert "| cached |" in out


def test_flight_table_shows_the_essentials():
    out = render_flights(result_with(make_quote(price=184, bookable=True)))
    assert "184 EUR" in out
    assert "NAP→LHR" in out
    assert "07:15" in out
    assert "3h05" in out
    assert "nonstop" in out


def test_all_cached_results_carry_an_explicit_caveat():
    out = render_flights(result_with(make_quote(price=96)))
    assert "No live bookable fares" in out
    assert "planning range" in out


def test_tight_connection_is_surfaced_as_a_warning():
    tight = make_quote(price=100, designator="LH100", connection=35)
    notes = warnings(result_with(tight))
    assert any("35 min" in n for n in notes)


def test_separate_ticket_risk_is_surfaced():
    split = make_quote(price=100, connection=90, carriers=["FR", "W6"])
    notes = warnings(result_with(split))
    assert any("separate tickets" in n for n in notes)


def test_cheapest_and_best_value_are_both_named_when_they_differ():
    cheap = make_quote(price=96, designator="FR8391", stops=2, duration=675)
    sane = make_quote(price=140, designator="BA2611", stops=0, duration=185)

    out = render_flights(result_with(cheap, sane))
    assert "**Cheapest** 96 EUR" in out
    assert "**Best value** 140 EUR" in out


def test_failed_providers_are_disclosed_not_hidden():
    out = render_flights(
        result_with(make_quote(price=184, bookable=True),
                    errors=[ProviderError("hotellook", "egress blocked", "blocked")])
    )
    assert "hotellook: egress blocked" in out


def test_unconfigured_tool_never_reads_as_an_empty_route():
    empty = SearchResult(providers_queried=[], query={"origin": "NAP", "destination": "LHR"})
    out = render_flights(empty)

    assert "No price sources are configured" in out
    assert "not a statement about availability" in out


def test_hotels_render_per_night_and_total():
    result = SearchResult(
        hotels=[
            HotelQuote(
                provider="hotellook",
                name="Ostello Vergini",
                price_total=Money.of(94, "EUR"),
                nights=3,
                stars=2,
                rating=7.9,
                neighborhood="Sanità",
            )
        ],
        providers_queried=["hotellook"],
        query={"location": "naples", "check_in": "2026-10-15", "check_out": "2026-10-18"},
    )
    out = render_hotels(result)
    assert "Ostello Vergini" in out
    assert "94 EUR" in out
    assert "31 EUR" in out  # per night, derived
    assert "Sanità" in out


def test_calendar_names_the_saving():
    calendar = {
        "2026-10-14": Money.of(128, "EUR"),
        "2026-10-15": Money.of(184, "EUR"),
        "2026-10-16": Money.of(96, "EUR"),
    }
    out = render_calendar(calendar, "NAP", "LHR")

    assert "2026-10-16" in out
    assert "worth 88 EUR" in out
    assert "not live availability" in out


def test_empty_calendar_says_so():
    assert "No price calendar" in render_calendar({}, "NAP", "LHR")


def test_unknown_stop_count_renders_as_unknown_not_nonstop():
    from travelagent.models import FlightQuote, Money, Slice

    summary = FlightQuote(
        provider="travelpayouts",
        price=Money.of(96, "EUR"),
        slices=[Slice("NAP", "LHR", [], is_summary=True)],
    )
    out = render_flights(result_with(summary))

    assert "nonstop" not in out
    assert "| — |" in out


def test_unbuyable_headline_price_names_the_real_cost():
    """The cheapest row being unbookable is the most useful caveat there is,
    so state the gap in money rather than leaving it to the labels."""
    cached = make_quote(provider="travelpayouts", price=96, designator="FR8391")
    live = make_quote(
        provider="duffel", price=122, designator="LH100", bookable=True,
        freshness=Freshness.LIVE,
    )
    out = render_flights(result_with(cached, live))

    assert "**Cheapest** 96 EUR (cached" in out
    assert "**Cheapest you can actually book** 122 EUR (bookable" in out
    assert "26 EUR above the headline" in out


def test_no_redundant_book_line_when_the_cheapest_is_bookable():
    live = make_quote(provider="duffel", price=122, bookable=True, freshness=Freshness.LIVE)
    out = render_flights(result_with(live))
    assert "Cheapest you can actually book" not in out
