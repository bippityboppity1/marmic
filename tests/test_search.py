"""Fan-out, dedupe, ranking and cache policy."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

import pytest

from travelagent.errors import NotConfigured, RateLimited
from travelagent.models import FlightQuote, Freshness, Money, Segment, Slice
from travelagent.providers.base import Provider
from travelagent.query import FlightSearch
from travelagent.search import (
    best_value_flight,
    dedupe_flights,
    rank_flights,
    search_flights,
)


def make_quote(
    provider="test",
    price=100,
    *,
    bookable=False,
    sandbox=False,
    freshness=Freshness.CACHED,
    designator="BA2611",
    depart="2026-10-15T07:15:00",
    stops=0,
    duration=185,
    connection=None,
    carriers=None,
):
    """Build a quote with real segment structure, not a stub."""
    depart_dt = datetime.fromisoformat(depart)
    if connection is not None:
        first_arrive = depart_dt.replace(hour=depart_dt.hour + 1)
        second_depart = first_arrive.replace(
            hour=first_arrive.hour + connection // 60,
            minute=first_arrive.minute + connection % 60,
        )
        segments = [
            Segment("NAP", "MUC", depart_dt, first_arrive, carrier=(carriers or ["LH"])[0],
                    flight_number="100"),
            Segment("MUC", "LHR", second_depart, None,
                    carrier=(carriers or ["LH"])[-1], flight_number="200"),
        ]
    else:
        code = designator[:2]
        segments = [
            Segment("NAP", "LHR", depart_dt, carrier=code,
                    flight_number=designator[2:])
        ]

    return FlightQuote(
        provider=provider,
        price=Money.of(price, "EUR"),
        slices=[Slice("NAP", "LHR", segments, duration_minutes=duration,
                      stop_count=stops if connection is None else None)],
        freshness=freshness,
        bookable=bookable,
        sandbox=sandbox,
    )


class StubProvider(Provider):
    supports_flights = True

    def __init__(self, config, name, quotes=None, error=None, cacheable=True):
        super().__init__(config)
        self.name = name
        self._quotes = quotes or []
        self._error = error
        self.cacheable = cacheable
        self.calls = 0

    @property
    def configured(self):
        return True

    def setup_hint(self):
        return "stub"

    async def search_flights(self, query, http):
        self.calls += 1
        if self._error:
            raise self._error
        return list(self._quotes)


@pytest.fixture
def query():
    return FlightSearch(origin="NAP", destination="LHR", depart_date="2026-10-15")


# --- fan-out --------------------------------------------------------------


async def test_merges_results_from_every_provider(config, query):
    providers = [
        StubProvider(config, "alpha", [make_quote(price=180)]),
        StubProvider(config, "beta", [make_quote(price=140, designator="FR8391")]),
    ]
    result = await search_flights(query, config, providers)

    assert len(result.flights) == 2
    assert result.providers_succeeded == ["alpha", "beta"]
    assert result.errors == []


async def test_one_provider_failing_never_sinks_the_rest(config, query):
    providers = [
        StubProvider(config, "alpha", [make_quote(price=180)]),
        StubProvider(config, "beta", error=RateLimited("slow down")),
        StubProvider(config, "gamma", error=NotConfigured("no token")),
    ]
    result = await search_flights(query, config, providers)

    assert [q.provider for q in result.flights] == ["test"]
    assert {e.provider for e in result.errors} == {"beta", "gamma"}
    assert {e.kind for e in result.errors} == {"rate_limited", "unconfigured"}
    assert result.providers_succeeded == ["alpha"]


async def test_unexpected_exceptions_are_captured_not_raised(config, query):
    providers = [StubProvider(config, "boom", error=ValueError("kaboom"))]
    result = await search_flights(query, config, providers)

    assert result.flights == []
    assert result.errors[0].message == "ValueError: kaboom"


async def test_flexible_dates_query_each_day(config):
    query = FlightSearch(
        origin="NAP",
        destination="LHR",
        depart_date="2026-10-15",
        date_flexibility_days=2,
    )
    provider = StubProvider(config, "alpha", [make_quote()])
    await search_flights(query, config, [provider])

    assert provider.calls == 5  # -2..+2


# --- dedupe ---------------------------------------------------------------


def test_identical_flights_collapse_to_one():
    quotes = [make_quote(provider="a", price=180), make_quote(provider="b", price=175)]
    assert len(dedupe_flights(quotes)) == 1


def test_bookable_wins_over_a_cheaper_cached_sighting():
    """A price you cannot buy is not a better price."""
    cached = make_quote(provider="travelpayouts", price=96, freshness=Freshness.CACHED)
    live = make_quote(provider="duffel", price=184, bookable=True, freshness=Freshness.LIVE)

    kept = dedupe_flights([cached, live])
    assert len(kept) == 1
    assert kept[0].provider == "duffel"
    assert kept[0].price.amount == Decimal("184")


def test_cheaper_wins_when_both_are_bookable():
    a = make_quote(provider="a", price=184, bookable=True, freshness=Freshness.LIVE)
    b = make_quote(provider="b", price=150, bookable=True, freshness=Freshness.LIVE)
    assert dedupe_flights([a, b])[0].price.amount == Decimal("150")


def test_different_flights_are_kept_apart():
    a = make_quote(designator="BA2611")
    b = make_quote(designator="FR8391")
    assert len(dedupe_flights([a, b])) == 2


# --- ranking --------------------------------------------------------------


def test_rank_sorts_by_price():
    quotes = [make_quote(price=p, designator=f"BA{p}") for p in (300, 100, 200)]
    assert [q.price.amount for q in rank_flights(quotes)] == [100, 200, 300]


def test_best_value_rejects_a_marginally_cheaper_multi_stop():
    cheap = make_quote(price=96, designator="FR8391", stops=2, duration=675)
    sane = make_quote(price=110, designator="BA2611", stops=0, duration=185)

    assert best_value_flight([cheap, sane]) is sane


def test_best_value_penalises_a_tight_connection():
    tight = make_quote(price=100, designator="LH100", connection=35, duration=400)
    comfortable = make_quote(price=118, designator="BA2611", stops=0, duration=185)

    assert best_value_flight([tight, comfortable]) is comfortable


def test_best_value_still_takes_a_big_saving():
    cheap = make_quote(price=100, designator="FR8391", stops=1, duration=300)
    dear = make_quote(price=600, designator="BA2611", stops=0, duration=185)

    assert best_value_flight([cheap, dear]) is cheap


def test_best_value_of_nothing_is_nothing():
    assert best_value_flight([]) is None


# --- cache policy ---------------------------------------------------------


async def test_cacheable_provider_is_only_called_once(config, query, tmp_path):
    config.cache_enabled = True
    config.cache_path = tmp_path / "c.sqlite3"
    provider = StubProvider(config, "travelpayouts", [make_quote()], cacheable=True)

    await search_flights(query, config, [provider])
    result = await search_flights(query, config, [provider])

    assert provider.calls == 1
    assert len(result.flights) == 1
    assert result.flights[0].price.amount == Decimal("100")


async def test_live_offers_are_never_served_from_cache(config, query, tmp_path):
    """Duffel offers expire. Re-serving one would quote a dead price."""
    config.cache_enabled = True
    config.cache_path = tmp_path / "c.sqlite3"
    provider = StubProvider(
        config, "duffel", [make_quote(bookable=True, freshness=Freshness.LIVE)],
        cacheable=False,
    )

    await search_flights(query, config, [provider])
    await search_flights(query, config, [provider])

    assert provider.calls == 2


async def test_a_different_query_is_a_different_cache_entry(config, tmp_path):
    config.cache_enabled = True
    config.cache_path = tmp_path / "c.sqlite3"
    provider = StubProvider(config, "travelpayouts", [make_quote()], cacheable=True)

    await search_flights(
        FlightSearch(origin="NAP", destination="LHR", depart_date="2026-10-15"),
        config, [provider],
    )
    await search_flights(
        FlightSearch(origin="NAP", destination="LHR", depart_date="2026-10-16"),
        config, [provider],
    )

    assert provider.calls == 2


def test_best_value_prices_time_against_the_fastest_option():
    """8 extra hours and 2 stops should cost more than a 46% fare premium."""
    slow = make_quote(price=96, designator="FR8391", stops=2, duration=675)
    fast = make_quote(price=140, designator="BA2611", stops=0, duration=185)

    assert best_value_flight([slow, fast]) is fast


def test_best_value_ignores_duration_when_no_source_reported_it():
    a = make_quote(price=100, designator="FR8391", stops=1, duration=None)
    b = make_quote(price=105, designator="BA2611", stops=0, duration=None)

    assert best_value_flight([a, b]) is b  # the stop is the only signal left


# --- provider health ------------------------------------------------------


async def test_doctor_separates_a_missing_key_from_an_unreachable_provider(config):
    """These look identical in a status table and have opposite remedies."""
    from travelagent.errors import Blocked
    from travelagent.search import probe_providers

    class NoKey(StubProvider):
        @property
        def configured(self):
            return False

    class Unreachable(StubProvider):
        async def probe(self, http):
            raise Blocked("egress blocked reaching https://api.duffel.com")

    class Healthy(StubProvider):
        async def probe(self, http):
            return "authenticated (test mode)"

    providers = [
        NoKey(config, "nokey"),
        Unreachable(config, "unreachable"),
        Healthy(config, "healthy"),
    ]
    import travelagent.providers.registry as registry
    original = registry.all_providers
    registry.all_providers = lambda cfg: providers
    try:
        rows = await probe_providers(config)
    finally:
        registry.all_providers = original

    states = {r.name: r.state for r in rows}
    assert states == {
        "nokey": "unconfigured",
        "unreachable": "failing",
        "healthy": "ready",
    }
    assert [r.name for r in rows if r.ok] == ["healthy"]


def test_doctor_footer_names_both_problems_separately():
    """A missing key and an unreachable host need different fixes, and both
    can be true at once — so the footer must never lump them together."""
    from travelagent.cli import _doctor_footer
    from travelagent.models import ProviderStatus

    rows = [
        ProviderStatus("duffel", "failing", "egress blocked"),
        ProviderStatus("travelpayouts", "unconfigured", "set a token"),
    ]
    footer = "\n".join(_doctor_footer(rows))

    assert "Unreachable: duffel" in footer
    assert "Not set up: travelpayouts" in footer
    assert "will not fix this" in footer


def test_doctor_footer_omits_a_category_that_is_empty():
    from travelagent.cli import _doctor_footer
    from travelagent.models import ProviderStatus

    footer = "\n".join(
        _doctor_footer([ProviderStatus("duffel", "unconfigured", "set a token")])
    )
    assert "Not set up: duffel" in footer
    assert "Unreachable" not in footer


def test_sandbox_survives_a_cache_round_trip():
    """The JSON surface feeds the MCP server, where the label does the work."""
    from travelagent.serde import flight_from_dict, flight_to_dict

    fake = make_quote(price=85, bookable=True, freshness=Freshness.LIVE, sandbox=True)
    payload = flight_to_dict(fake)
    assert payload["sandbox"] is True
    assert flight_from_dict(payload).sandbox is True
