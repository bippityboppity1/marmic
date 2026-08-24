"""Markdown rendering.

The trip-planning skill wants tables it can paste into a plan, and it needs
provenance visible in the table rather than buried in a footnote — a reader
scanning a price column should be able to see which numbers are bookable and
which are weather reports.
"""

from __future__ import annotations

from datetime import UTC, datetime

from .models import FlightQuote, SearchResult
from .search import TIGHT_CONNECTION_MINUTES, best_value_flight


def _fmt_time(value: datetime | None) -> str:
    return value.strftime("%H:%M") if value else "—"


def _fmt_duration(minutes: int | None) -> str:
    if not minutes:
        return "—"
    return f"{minutes // 60}h{minutes % 60:02d}"


def _stops(quote: FlightQuote) -> str:
    stops = quote.stops
    return "nonstop" if stops == 0 else f"{stops} stop" + ("s" if stops > 1 else "")


def _route(quote: FlightQuote) -> str:
    if not quote.slices:
        return "—"
    out = quote.slices[0]
    label = f"{out.origin}→{out.destination}"
    if len(quote.slices) > 1:
        label += f" / {quote.slices[1].origin}→{quote.slices[1].destination}"
    return label


def no_sources_note(result: SearchResult) -> str | None:
    """Distinguish "nothing is configured" from "this route has nothing".

    These read identically in a table and mean opposite things: one is a
    five-minute setup fix, the other changes the trip. Never blur them.
    """
    if result.providers_queried:
        return None
    return (
        "**No price sources are configured**, so this is not a statement about availability — "
        "nothing was searched. Run `travelagent doctor` and add at least one token."
    )


def flights_table(result: SearchResult, limit: int = 10) -> str:
    if note := no_sources_note(result):
        return note
    if not result.flights:
        return "_No fares returned._"

    rows = [
        "| Price | Basis | Route | Depart | Arrive | Duration | Stops | Airline | Source |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for q in result.flights[:limit]:
        out = q.slices[0] if q.slices else None
        carrier = ", ".join(q.carriers) or (
            out.segments[0].carrier_name if out and out.segments else "—"
        )
        basis = "**bookable**" if q.bookable else q.freshness.label
        rows.append(
            "| {price} | {basis} | {route} | {dep} | {arr} | {dur} | {stops} | {carrier} | {src} |".format(
                price=q.price,
                basis=basis,
                route=_route(q),
                dep=_fmt_time(out.depart if out else None),
                arr=_fmt_time(out.arrive if out else None),
                dur=_fmt_duration(q.total_duration_minutes),
                stops=_stops(q),
                carrier=carrier or "—",
                src=q.provider,
            )
        )
    return "\n".join(rows)


def hotels_table(result: SearchResult, limit: int = 10) -> str:
    if note := no_sources_note(result):
        return note
    if not result.hotels:
        return "_No hotel prices returned._"

    rows = [
        "| Property | Per night | Total | Basis | Stars | Rating | Area | Source |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for h in result.hotels[:limit]:
        rows.append(
            "| {name} | {pn} | {total} | {basis} | {stars} | {rating} | {area} | {src} |".format(
                name=h.name,
                pn=h.price_per_night or "—",
                total=h.price_total,
                basis="**bookable**" if h.bookable else h.freshness.label,
                stars=f"{h.stars:g}" if h.stars else "—",
                rating=f"{h.rating:g}" if h.rating else "—",
                area=h.neighborhood or "—",
                src=h.provider,
            )
        )
    return "\n".join(rows)


def warnings(result: SearchResult) -> list[str]:
    """The things that actually break trips, surfaced before the price."""
    notes: list[str] = []

    tight = [
        q
        for q in result.flights
        if (m := q.shortest_connection_minutes) is not None
        and m < TIGHT_CONNECTION_MINUTES
    ]
    if tight:
        worst = min(q.shortest_connection_minutes or 0 for q in tight)
        notes.append(
            f"{len(tight)} option(s) include a connection as short as {worst} min — "
            "below the level worth booking without a protected ticket."
        )

    split = [q for q in result.flights if q.is_self_transfer]
    if split:
        notes.append(
            f"{len(split)} option(s) look like separate tickets across carriers: "
            "no protection if the first leg slips."
        )

    if result.flights and not any(q.bookable for q in result.flights):
        notes.append(
            "No live bookable fares in these results — every price here is a "
            "cached sighting and may already be gone. Treat as a planning range."
        )

    return notes


def provenance_footer(result: SearchResult) -> str:
    stamp = datetime.now(UTC).strftime("%Y-%m-%d %H:%M UTC")
    lines = [f"_Prices read {stamp}. Sources: {', '.join(result.providers_succeeded) or 'none'}._"]
    for err in result.errors:
        lines.append(f"_{err.provider}: {err.message}_")
    return "\n".join(lines)


def render_flights(result: SearchResult, limit: int = 10) -> str:
    q = result.query
    header = f"### Fares {q.get('origin')} → {q.get('destination')}, {q.get('depart')}"
    if q.get("return"):
        header += f" returning {q['return']}"

    parts = [header, "", flights_table(result, limit)]

    cheapest = result.cheapest_flight()
    value = best_value_flight(result.flights)
    if cheapest and value and value is not cheapest:
        parts += [
            "",
            f"**Cheapest** {cheapest.price} ({_stops(cheapest)}) · "
            f"**Best value** {value.price} ({_stops(value)}, "
            f"{_fmt_duration(value.total_duration_minutes)}) — "
            "the gap buys back time or removes a fragile connection.",
        ]
    elif cheapest:
        parts += ["", f"**Cheapest** {cheapest.price} ({_stops(cheapest)})."]

    if notes := warnings(result):
        parts += ["", *(f"- {n}" for n in notes)]

    parts += ["", provenance_footer(result)]
    return "\n".join(parts)


def render_hotels(result: SearchResult, limit: int = 10) -> str:
    q = result.query
    header = (
        f"### Stays in {q.get('location', '').title()}, "
        f"{q.get('check_in')} → {q.get('check_out')}"
    )
    parts = [header, "", hotels_table(result, limit)]
    if notes := warnings(result):
        parts += ["", *(f"- {n}" for n in notes)]
    parts += ["", provenance_footer(result)]
    return "\n".join(parts)


def render_calendar(calendar: dict, origin: str, destination: str) -> str:
    if not calendar:
        return "_No price calendar available for this route._"

    cheapest_day = min(calendar, key=lambda d: calendar[d].amount)
    dearest_day = max(calendar, key=lambda d: calendar[d].amount)
    spread = calendar[dearest_day].amount - calendar[cheapest_day].amount

    rows = [
        f"### Cheapest departure dates {origin} → {destination}",
        "",
        "| Date | Cheapest fare |",
        "| --- | --- |",
    ]
    for day, price in sorted(calendar.items(), key=lambda kv: kv[1].amount)[:10]:
        marker = " ←" if day == cheapest_day else ""
        rows.append(f"| {day} | {price}{marker} |")

    rows += [
        "",
        f"**Best date {cheapest_day} at {calendar[cheapest_day]}**, "
        f"against {calendar[dearest_day]} on {dearest_day} — "
        f"moving the date is worth {spread:,.0f} {calendar[cheapest_day].currency}.",
        "",
        "_Cached fare data, not live availability — confirm before booking._",
    ]
    return "\n".join(rows)
