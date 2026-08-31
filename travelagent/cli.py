"""Command line entry point.

argparse rather than a CLI framework: one less dependency, and this surface
is small enough that the framework would not earn its keep.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import date

from .config import Config
from .errors import TravelAgentError
from .http import HttpClient
from .query import FlightSearch, GroundSearch, HotelSearch
from .report import render_calendar, render_flights, render_ground, render_hotels
from .search import probe_providers, search_flights, search_ground, search_hotels
from .serde import flight_to_dict, ground_to_dict, hotel_to_dict


def _add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--currency", help="ISO currency code (default from config)")
    parser.add_argument("--limit", type=int, default=10, help="rows to show")
    parser.add_argument("--json", action="store_true", help="emit JSON instead of markdown")
    parser.add_argument("--no-cache", action="store_true", help="bypass the price cache")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="travelagent", description="Real travel prices with provenance."
    )
    sub = parser.add_subparsers(dest="command", required=True)

    flights = sub.add_parser("flights", help="search fares")
    flights.add_argument("origin", help="IATA code, e.g. NAP")
    flights.add_argument("destination", help="IATA code, e.g. LHR")
    flights.add_argument("--depart", required=True, help="YYYY-MM-DD")
    flights.add_argument("--return", dest="return_date", help="YYYY-MM-DD")
    flights.add_argument("--adults", type=int, default=1)
    flights.add_argument("--children", type=int, default=0)
    flights.add_argument("--infants", type=int, default=0)
    flights.add_argument("--cabin", default="economy")
    flights.add_argument("--max-connections", type=int)
    flights.add_argument(
        "--flex", type=int, default=0, metavar="DAYS",
        help="also check +/- DAYS around the departure date",
    )
    _add_common(flights)

    hotels = sub.add_parser("hotels", help="search stays")
    hotels.add_argument("location", help="city name or IATA code")
    hotels.add_argument("--checkin", required=True, help="YYYY-MM-DD")
    hotels.add_argument("--checkout", required=True, help="YYYY-MM-DD")
    hotels.add_argument("--adults", type=int, default=2)
    hotels.add_argument("--rooms", type=int, default=1)
    hotels.add_argument("--max-per-night", type=float)
    hotels.add_argument("--min-stars", type=float)
    _add_common(hotels)

    calendar = sub.add_parser("calendar", help="cheapest departure dates in a month")
    calendar.add_argument("origin")
    calendar.add_argument("destination")
    calendar.add_argument("--month", required=True, help="YYYY-MM")
    calendar.add_argument("--currency")

    drive = sub.add_parser("drive", help="price a journey by road")
    drive.add_argument("origin", help="place name, e.g. Giovinazzo")
    drive.add_argument("destination", help="place name, e.g. Matera")
    drive.add_argument("--depart", help="YYYY-MM-DD (optional)")
    drive.add_argument("--passengers", type=int, default=1)
    _add_common(drive)

    sub.add_parser("doctor", help="check every provider's credentials and contract")

    cache = sub.add_parser("cache", help="inspect or clear the price cache")
    cache.add_argument("--clear", action="store_true")

    return parser


async def _cmd_flights(args, config: Config) -> str:
    query = FlightSearch(
        origin=args.origin,
        destination=args.destination,
        depart_date=args.depart,
        return_date=args.return_date,
        adults=args.adults,
        children=args.children,
        infants=args.infants,
        cabin=args.cabin,
        max_connections=args.max_connections,
        currency=args.currency or config.currency,
        date_flexibility_days=args.flex,
    )
    result = await search_flights(query, config)
    if args.json:
        return json.dumps(
            {
                "query": result.query,
                "flights": [flight_to_dict(q) for q in result.flights[: args.limit]],
                "errors": [vars(e) for e in result.errors],
            },
            indent=2,
        )
    return render_flights(result, args.limit)


async def _cmd_hotels(args, config: Config) -> str:
    from .providers.registry import active_providers

    if not active_providers(config, "hotels"):
        return (
            "_No hotel price source is available._ Hotellook, the only one this "
            "package shipped with, was shut down by Travelpayouts and its API no "
            "longer answers. Flights are unaffected. Restoring hotels means "
            "adding a new provider adapter, not changing a token."
        )

    query = HotelSearch(
        location=args.location,
        check_in=args.checkin,
        check_out=args.checkout,
        adults=args.adults,
        rooms=args.rooms,
        currency=args.currency or config.currency,
        limit=max(args.limit, 20),
        max_price_per_night=args.max_per_night,
        min_stars=args.min_stars,
    )
    result = await search_hotels(query, config)
    if args.json:
        return json.dumps(
            {
                "query": result.query,
                "hotels": [hotel_to_dict(h) for h in result.hotels[: args.limit]],
                "errors": [vars(e) for e in result.errors],
            },
            indent=2,
        )
    return render_hotels(result, args.limit)


async def _cmd_calendar(args, config: Config) -> str:
    from .providers.travelpayouts import TravelpayoutsProvider

    provider = TravelpayoutsProvider(config)
    if not provider.configured:
        return f"_Price calendar needs Travelpayouts._ {provider.setup_hint()}"

    month = date.fromisoformat(f"{args.month}-01")
    query = FlightSearch(
        origin=args.origin,
        destination=args.destination,
        depart_date=month,
        currency=args.currency or config.currency,
    )
    async with HttpClient(config.timeout_seconds, config.max_retries) as http:
        calendar = await provider.price_calendar(query, http, month=month)
    return render_calendar(calendar, query.origin, query.destination)


async def _cmd_drive(args, config: Config) -> str:
    query = GroundSearch(
        origin=args.origin,
        destination=args.destination,
        depart_date=args.depart,
        passengers=args.passengers,
        currency=args.currency or config.currency,
    )
    result = await search_ground(query, config)
    if args.json:
        return json.dumps(
            {
                "query": result.query,
                "journeys": [ground_to_dict(g) for g in result.ground],
                "errors": [vars(e) for e in result.errors],
            },
            indent=2,
        )
    return render_ground(result)


def _doctor_footer(rows) -> list[str]:
    """Say precisely which of the two problems each provider has.

    Both can be true at once, and Hotellook needs no credentials at all — so
    never assert that a token is set. A missing key and an unreachable host
    look identical in a status table and have opposite remedies.
    """
    failing = [r.name for r in rows if r.state == "failing"]
    unconfigured = [r.name for r in rows if r.state == "unconfigured"]
    retired = [r.name for r in rows if r.state == "retired"]

    lines = ["", "**No price source is usable right now.**"]
    if failing:
        lines.append(
            f"- Unreachable: {', '.join(failing)} — a network or provider problem. "
            "Adding or changing a token will not fix this."
        )
    if unconfigured:
        lines.append(f"- Not set up: {', '.join(unconfigured)} — see the hints above.")
    if retired:
        lines.append(
            f"- Gone for good: {', '.join(retired)} — the upstream service shut down. "
            "Not something to debug; it needs replacing."
        )
    return lines


MARKS = {
    "ready": "✅ ready",
    "unconfigured": "⚪ no key",
    "failing": "❌ failing",
    "retired": "🪦 retired",
}


async def _cmd_doctor(config: Config) -> str:
    rows = await probe_providers(config)
    lines = ["| Provider | Status | Detail |", "| --- | --- | --- |"]
    for row in rows:
        lines.append(f"| {row.name} | {MARKS[row.state]} | {row.detail} |")

    if not any(row.ok for row in rows):
        lines += _doctor_footer(rows)
    return "\n".join(lines)


def _cmd_cache(args, config: Config) -> str:
    from .cache import PriceCache

    cache = PriceCache(config.cache_path, config.cache_ttl_seconds, True)
    if args.clear:
        cache.clear()
        cache.close()
        return f"Cache cleared ({config.cache_path})."
    purged = cache.purge_expired()
    cache.close()
    return f"Cache at {config.cache_path}; purged {purged} expired row(s)."


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    config = Config.from_env()
    if getattr(args, "no_cache", False):
        config.cache_enabled = False

    try:
        if args.command == "flights":
            print(asyncio.run(_cmd_flights(args, config)))
        elif args.command == "hotels":
            print(asyncio.run(_cmd_hotels(args, config)))
        elif args.command == "calendar":
            print(asyncio.run(_cmd_calendar(args, config)))
        elif args.command == "drive":
            print(asyncio.run(_cmd_drive(args, config)))
        elif args.command == "doctor":
            print(asyncio.run(_cmd_doctor(config)))
        elif args.command == "cache":
            print(_cmd_cache(args, config))
    except TravelAgentError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
