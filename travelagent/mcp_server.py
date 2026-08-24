"""MCP server — exposes live pricing to Claude during a planning conversation.

Run it with:  travelagent-mcp
Register it with:  claude mcp add travelagent -- travelagent-mcp

Every tool returns markdown with provenance already in the table, so the
model never has to guess whether a number is bookable.
"""

from __future__ import annotations

from datetime import date

from mcp.server import MCPServer

from .config import Config
from .errors import TravelAgentError
from .http import HttpClient
from .query import FlightSearch, HotelSearch
from .report import render_calendar, render_flights, render_hotels
from .search import probe_providers, search_flights, search_hotels

INSTRUCTIONS = """\
Live travel pricing. Three rules when using these results:

1. A row marked **bookable** is a real, sellable fare — quote it as a price.
   A row marked `cached` is a fare someone saw recently and may be gone —
   quote it as a range or an indication, never as a price.
2. Prices carry a read timestamp. If it is more than an hour old in the
   conversation, re-run the search rather than repeating the old number.
3. `cheapest_dates` before `search_flights` when dates are flexible. Moving
   the date usually saves more than changing the airline.
"""

server = MCPServer(
    name="travelagent",
    version="0.1.0",
    instructions=INSTRUCTIONS,
)


def _config() -> Config:
    return Config.from_env()


@server.tool(
    name="search_flights",
    description=(
        "Search real flight prices for a route and date. Returns a markdown "
        "table of fares with each price labelled bookable (live, sellable) or "
        "cached (recently seen, may be gone), plus stops, duration, airline "
        "and warnings about tight connections or separate tickets. Use "
        "flexible_days to check nearby departure dates in the same call."
    ),
)
async def search_flights_tool(
    origin: str,
    destination: str,
    depart_date: str,
    return_date: str | None = None,
    adults: int = 1,
    children: int = 0,
    cabin: str = "economy",
    currency: str | None = None,
    max_connections: int | None = None,
    flexible_days: int = 0,
    limit: int = 10,
) -> str:
    """Search fares. Dates are YYYY-MM-DD; airports are IATA codes."""
    config = _config()
    try:
        query = FlightSearch(
            origin=origin,
            destination=destination,
            depart_date=depart_date,
            return_date=return_date,
            adults=adults,
            children=children,
            cabin=cabin,
            currency=currency or config.currency,
            max_connections=max_connections,
            date_flexibility_days=flexible_days,
        )
    except TravelAgentError as exc:
        return f"Invalid search: {exc}"

    result = await search_flights(query, config)
    if not result.flights and (result.errors or not result.providers_queried):
        return _no_results(result, "fares")
    return render_flights(result, limit)


@server.tool(
    name="search_hotels",
    description=(
        "Search real hotel prices for a location and date range. Returns a "
        "markdown table with per-night and total price, provenance label, "
        "star rating, guest rating and area. Filter with max_price_per_night "
        "or min_stars."
    ),
)
async def search_hotels_tool(
    location: str,
    check_in: str,
    check_out: str,
    adults: int = 2,
    rooms: int = 1,
    currency: str | None = None,
    max_price_per_night: float | None = None,
    min_stars: float | None = None,
    limit: int = 10,
) -> str:
    """Search stays. Location is a city name or IATA code."""
    config = _config()
    try:
        query = HotelSearch(
            location=location,
            check_in=check_in,
            check_out=check_out,
            adults=adults,
            rooms=rooms,
            currency=currency or config.currency,
            limit=max(limit, 20),
            max_price_per_night=max_price_per_night,
            min_stars=min_stars,
        )
    except TravelAgentError as exc:
        return f"Invalid search: {exc}"

    result = await search_hotels(query, config)
    if not result.hotels and (result.errors or not result.providers_queried):
        return _no_results(result, "hotel prices")
    return render_hotels(result, limit)


@server.tool(
    name="cheapest_dates",
    description=(
        "Show the cheapest departure dates across a whole month for a route. "
        "Call this first whenever dates are flexible — shifting the date "
        "usually saves more than any other single change. Returns a ranked "
        "date table and the size of the saving."
    ),
)
async def cheapest_dates_tool(
    origin: str,
    destination: str,
    month: str,
    currency: str | None = None,
) -> str:
    """month is YYYY-MM."""
    from .providers.travelpayouts import TravelpayoutsProvider

    config = _config()
    provider = TravelpayoutsProvider(config)
    if not provider.configured:
        return f"Price calendar unavailable. {provider.setup_hint()}"

    try:
        target = date.fromisoformat(f"{month}-01")
        query = FlightSearch(
            origin=origin,
            destination=destination,
            depart_date=target,
            currency=currency or config.currency,
        )
    except (TravelAgentError, ValueError) as exc:
        return f"Invalid request: {exc}"

    try:
        async with HttpClient(config.timeout_seconds, config.max_retries) as http:
            calendar = await provider.price_calendar(query, http, month=target)
    except TravelAgentError as exc:
        return f"Price calendar failed: {exc}"

    return render_calendar(calendar, query.origin, query.destination)


@server.tool(
    name="provider_status",
    description=(
        "Check which price sources are configured and reachable. Call this "
        "when a search returns nothing, to tell a missing API key apart from "
        "a route that genuinely has no flights."
    ),
)
async def provider_status_tool() -> str:
    rows = await probe_providers(_config())
    lines = ["| Provider | Status | Detail |", "| --- | --- | --- |"]
    for name, ok, detail in rows:
        lines.append(f"| {name} | {'ready' if ok else 'off'} | {detail} |")
    if not any(ok for _n, ok, _d in rows):
        lines += ["", "No price sources are configured — every result will be empty."]
    return "\n".join(lines)


def _no_results(result, noun: str) -> str:
    """Never let a tooling failure read as 'this route has nothing'."""
    if not result.providers_queried:
        return (
            f"No {noun} returned because **no price sources are configured** — "
            "nothing was searched, so this says nothing about availability. "
            "Run `provider_status` for setup instructions."
        )
    detail = "\n".join(f"- {e.provider}: {e.message}" for e in result.errors)
    return (
        f"No {noun} returned, and every source errored — this is a tooling "
        f"failure, not an empty route:\n{detail}\n\n"
        "Run `provider_status` to check credentials."
    )


def main() -> None:
    server.run("stdio")


if __name__ == "__main__":
    main()
