"""Fan out across providers, merge honestly, rank usefully."""

from __future__ import annotations

import asyncio
from collections.abc import Iterable
from typing import Any

from .cache import PriceCache, cache_key
from .config import Config
from .errors import TravelAgentError
from .http import HttpClient
from .models import (
    FlightQuote,
    Freshness,
    HotelQuote,
    ProviderError,
    ProviderStatus,
    SearchResult,
)
from .providers.base import Provider
from .providers.registry import active_providers
from .query import FlightSearch, HotelSearch
from .serde import flight_from_dict, flight_to_dict, hotel_from_dict, hotel_to_dict

# Connections tighter than this are the single most common way a plan breaks.
TIGHT_CONNECTION_MINUTES = 60


async def search_flights(
    query: FlightSearch,
    config: Config | None = None,
    providers: list[Provider] | None = None,
) -> SearchResult:
    config = config or Config.from_env()
    providers = providers if providers is not None else active_providers(config, "flights")

    searches = query.date_window()
    result = await _fan_out(
        config,
        providers,
        [
            (p, q)
            for p in providers
            for q in (
                [query]
                if len(searches) == 1
                else [query.shifted((d - query.depart_date).days) for d in searches]
            )
        ],
        kind="flights",
    )
    result.query = query.cache_payload()
    result.flights = rank_flights(dedupe_flights(result.flights))
    return result


async def search_hotels(
    query: HotelSearch,
    config: Config | None = None,
    providers: list[Provider] | None = None,
) -> SearchResult:
    config = config or Config.from_env()
    providers = providers if providers is not None else active_providers(config, "hotels")

    result = await _fan_out(config, providers, [(p, query) for p in providers], kind="hotels")
    result.query = query.cache_payload()
    result.hotels = rank_hotels(dedupe_hotels(result.hotels))
    return result


async def _fan_out(
    config: Config,
    providers: list[Provider],
    jobs: list[tuple[Provider, Any]],
    kind: str,
) -> SearchResult:
    """Run every provider concurrently; one failure never sinks the rest."""
    result = SearchResult(providers_queried=[p.name for p in providers])
    if not jobs:
        return result

    cache = PriceCache(config.cache_path, config.cache_ttl_seconds, config.cache_enabled)
    to_dict = flight_to_dict if kind == "flights" else hotel_to_dict
    from_dict = flight_from_dict if kind == "flights" else hotel_from_dict

    async def run(provider: Provider, query: Any) -> tuple[Provider, list[Any]]:
        key = cache_key(provider.name, {"kind": kind, **query.cache_payload()})

        if provider.cacheable:
            hit = cache.get(key)
            if hit is not None:
                return provider, [from_dict(row) for row in hit]

        method = getattr(provider, f"search_{kind}")
        quotes = await method(query, http)

        if provider.cacheable and quotes:
            cache.set(key, provider.name, [to_dict(q) for q in quotes])
        return provider, quotes

    try:
        async with HttpClient(config.timeout_seconds, config.max_retries) as http:
            outcomes = await asyncio.gather(
                *(run(p, q) for p, q in jobs), return_exceptions=True
            )

        seen_errors: set[tuple[str, str]] = set()
        for outcome, (provider, _q) in zip(outcomes, jobs, strict=True):
            if isinstance(outcome, BaseException):
                err = _to_provider_error(provider, outcome)
                token = (err.provider, err.message)
                if token not in seen_errors:
                    seen_errors.add(token)
                    result.errors.append(err)
                continue
            _provider, quotes = outcome
            if kind == "flights":
                result.flights.extend(quotes)
            else:
                result.hotels.extend(quotes)
    finally:
        cache.close()

    return result


def _to_provider_error(provider: Provider, exc: BaseException) -> ProviderError:
    if isinstance(exc, TravelAgentError):
        return ProviderError(provider.name, str(exc), kind=exc.kind)
    if isinstance(exc, asyncio.TimeoutError):
        return ProviderError(provider.name, "timed out", kind="timeout")
    return ProviderError(provider.name, f"{type(exc).__name__}: {exc}")


def dedupe_flights(quotes: Iterable[FlightQuote]) -> list[FlightQuote]:
    """Same metal from two sources: keep the one you can act on.

    A live bookable offer beats a cached sighting even when the cached one is
    cheaper, because the cheaper number may not exist. Ties break on price.
    """
    best: dict[tuple, FlightQuote] = {}
    loose: list[FlightQuote] = []

    for quote in quotes:
        sig = quote.signature()
        if not sig:
            loose.append(quote)
            continue
        incumbent = best.get(sig)
        if incumbent is None or _flight_precedence(quote) < _flight_precedence(incumbent):
            best[sig] = quote
    return list(best.values()) + loose


def _flight_precedence(quote: FlightQuote) -> tuple:
    freshness_rank = {Freshness.LIVE: 0, Freshness.CACHED: 1, Freshness.ESTIMATE: 2}
    return (
        0 if quote.bookable else 1,
        freshness_rank[quote.freshness],
        quote.price.amount,
    )


def dedupe_hotels(quotes: Iterable[HotelQuote]) -> list[HotelQuote]:
    best: dict[tuple, HotelQuote] = {}
    for quote in quotes:
        sig = quote.signature()
        incumbent = best.get(sig)
        if incumbent is None or quote.price_total.amount < incumbent.price_total.amount:
            best[sig] = quote
    return list(best.values())


def rank_flights(quotes: list[FlightQuote]) -> list[FlightQuote]:
    # Unknown stop counts sort after known ones at the same price: a
    # confirmed nonstop is worth more than an unspecified itinerary.
    return sorted(
        quotes,
        key=lambda q: (q.price.amount, 99 if q.stops is None else q.stops),
    )


def rank_hotels(quotes: list[HotelQuote]) -> list[HotelQuote]:
    return sorted(quotes, key=lambda q: q.price_total.amount)


def best_value_flight(quotes: list[FlightQuote]) -> FlightQuote | None:
    """Cheapest is rarely best. Price the pain.

    Everything is scored in the currency of the search, against two
    baselines: the cheapest fare and the shortest journey. A candidate pays
    for the money it costs above the cheapest, plus a notional cost for every
    stop and every hour it is slower than the fastest option on the table.

    Measuring time against the fastest option rather than in the absolute is
    what makes this work — eight extra hours should cost real money in the
    ranking, and an absolute per-hour rate cannot tell a long-haul from a
    detour.

    The weights are deliberately crude and legible. They exist to stop the
    cheapest four-stop red-eye winning by default, not to model anyone's
    true preferences.
    """
    if not quotes:
        return None

    cheapest = float(min(q.price.amount for q in quotes))
    durations = [q.total_duration_minutes for q in quotes if q.total_duration_minutes]
    fastest = min(durations) if durations else None

    per_stop = cheapest * 0.15
    per_slow_hour = cheapest * 0.08
    tight_connection_penalty = cheapest * 0.35
    self_transfer_penalty = cheapest * 0.20
    unbookable_penalty = cheapest * 0.05

    def score(q: FlightQuote) -> float:
        penalty = float(q.price.amount) - cheapest
        # An unknown stop count is not a free pass, but it is not evidence
        # of a stop either — charge it as though it were one connection.
        penalty += (1 if q.stops is None else q.stops) * per_stop

        if fastest and q.total_duration_minutes:
            slower_hours = max(0, q.total_duration_minutes - fastest) / 60
            penalty += slower_hours * per_slow_hour

        tight = q.shortest_connection_minutes
        if tight is not None and tight < TIGHT_CONNECTION_MINUTES:
            penalty += tight_connection_penalty
        if q.is_self_transfer:
            penalty += self_transfer_penalty
        if not q.bookable:
            penalty += unbookable_penalty

        return penalty

    return min(quotes, key=score)


async def probe_providers(config: Config) -> list[ProviderStatus]:
    """Health check every provider. Powers `travelagent doctor`."""
    from .providers.registry import all_providers

    rows: list[ProviderStatus] = []
    async with HttpClient(config.timeout_seconds, max_retries=0) as http:
        for provider in all_providers(config):
            if getattr(provider, "retired", False):
                rows.append(
                    ProviderStatus(provider.name, "retired", provider.setup_hint())
                )
                continue
            if not provider.configured:
                rows.append(
                    ProviderStatus(provider.name, "unconfigured", provider.setup_hint())
                )
                continue
            try:
                detail = await provider.probe(http)
            except TravelAgentError as exc:
                rows.append(ProviderStatus(provider.name, "failing", str(exc)))
            except Exception as exc:
                rows.append(
                    ProviderStatus(
                        provider.name, "failing", f"{type(exc).__name__}: {exc}"
                    )
                )
            else:
                rows.append(ProviderStatus(provider.name, "ready", detail))
    return rows
