"""Google Flights via a headless browser.

Parsing strategy matters here. CSS class names on this page are generated and
churn constantly, so keying off them guarantees a broken scraper within
weeks. Instead we read the accessibility labels, which are written for screen
readers and are both stable and richly structured:

  "From 123 euros. Nonstop flight with British Airways. Leaves London
   Heathrow Airport at 7:15 AM on Saturday, September 12 and arrives at
   Naples International Airport at 10:55 AM. Total duration 2 hr 40 min."

That single string carries price, stops, carrier, both times and duration.
The extraction below is a pure function over HTML so it can be tested
without a browser.
"""

from __future__ import annotations

import html as html_lib
import re
from datetime import datetime
from datetime import time as dtime
from urllib.parse import urlencode

from ..models import FlightQuote, Freshness, Money, Segment, Slice
from ..query import FlightSearch
from ._shared import CURRENCY_WORDS, parse_amount, parse_clock
from .base import ScraperProvider

RESULTS_SELECTOR = 'li[class*="pIav2d"], ul[class*="Rk10dc"] li, [role="listitem"]'

ARIA_RE = re.compile(r'aria-label="([^"]{40,600})"')
PRICE_RE = re.compile(
    r"From\s+([\d.,]+)\s*(euros|US dollars|dollars|pounds|pound sterling|"
    r"Swiss francs|Japanese yen|yen)",
    re.IGNORECASE,
)
SYMBOL_PRICE_RE = re.compile(r"From\s*([€$£¥])\s*([\d.,]+)", re.IGNORECASE)
STOPS_RE = re.compile(r"\b(Nonstop|(\d+)\s+stops?)\b", re.IGNORECASE)
CARRIER_RE = re.compile(r"flights?\s+with\s+([A-Z][^.]{2,60}?)(?:\.|\s+Leaves)", re.IGNORECASE)
LEAVES_RE = re.compile(r"Leaves\s+(.+?)\s+at\s+(\d{1,2}:\d{2}\s*[AP]M)", re.IGNORECASE)
ARRIVES_RE = re.compile(r"arrives\s+at\s+(.+?)\s+at\s+(\d{1,2}:\d{2}\s*[AP]M)", re.IGNORECASE)
DURATION_RE = re.compile(
    r"Total duration\s+(?:(\d+)\s*hr)?\s*(?:(\d+)\s*min)?", re.IGNORECASE
)

SYMBOL_TO_CODE = {"€": "EUR", "$": "USD", "£": "GBP", "¥": "JPY"}


def build_url(query: FlightSearch) -> str:
    """Google Flights honours a natural-language q= parameter."""
    q = (
        f"Flights to {query.destination} from {query.origin} "
        f"on {query.depart_date.isoformat()}"
    )
    if query.return_date:
        q += f" through {query.return_date.isoformat()}"
    if query.cabin != "economy":
        q += f" {query.cabin.replace('_', ' ')}"
    params = {"q": q, "curr": query.currency, "hl": "en", "gl": "us"}
    return f"https://www.google.com/travel/flights?{urlencode(params)}"


def parse_flights(page_html: str, query: FlightSearch) -> list[FlightQuote]:
    """Extract quotes from rendered Google Flights HTML."""
    quotes: list[FlightQuote] = []
    seen: set[str] = set()

    for match in ARIA_RE.finditer(page_html):
        label = html_lib.unescape(match.group(1))
        if "From" not in label or "duration" not in label.lower():
            continue
        if label in seen:
            continue
        seen.add(label)

        quote = _parse_label(label, query)
        if quote:
            quotes.append(quote)
    return quotes


def _parse_label(label: str, query: FlightSearch) -> FlightQuote | None:
    amount: float | None = None
    currency = query.currency

    if m := PRICE_RE.search(label):
        amount = parse_amount(m.group(1))
        currency = CURRENCY_WORDS.get(m.group(2).lower(), query.currency)
    elif m := SYMBOL_PRICE_RE.search(label):
        currency = SYMBOL_TO_CODE.get(m.group(1), query.currency)
        amount = parse_amount(m.group(2))

    if amount is None:
        return None

    stops = 0
    if m := STOPS_RE.search(label):
        stops = 0 if m.group(1).lower() == "nonstop" else int(m.group(2) or 0)

    carrier_name = ""
    if m := CARRIER_RE.search(label):
        carrier_name = m.group(1).strip()

    depart_t = arrive_t = None
    if m := LEAVES_RE.search(label):
        depart_t = parse_clock(m.group(2))
    if m := ARRIVES_RE.search(label):
        arrive_t = parse_clock(m.group(2))

    duration = None
    if m := DURATION_RE.search(label):
        hours, minutes = m.group(1), m.group(2)
        if hours or minutes:
            duration = int(hours or 0) * 60 + int(minutes or 0)

    depart_dt = datetime.combine(query.depart_date, depart_t or dtime(0, 0))
    arrive_dt = (
        datetime.combine(query.depart_date, arrive_t) if arrive_t else None
    )
    if arrive_dt and arrive_dt < depart_dt:
        arrive_dt = None  # crosses midnight; the label does not say which day

    # The label describes the journey as a whole, not leg by leg. Represent
    # it as a single segment and carry the stop count explicitly: inventing
    # one segment per stop would imply connection times the page never gave
    # us, and those would surface as bogus tight-connection warnings.
    segment = Segment(
        origin=query.origin,
        destination=query.destination,
        depart=depart_dt,
        arrive=arrive_dt,
        carrier_name=carrier_name,
        duration_minutes=duration,
    )
    slice_ = Slice(
        origin=query.origin,
        destination=query.destination,
        segments=[segment],
        duration_minutes=duration,
        stop_count=stops,
    )

    return FlightQuote(
        provider="google_flights",
        price=Money.of(amount, currency),
        slices=[slice_],
        freshness=Freshness.LIVE,
        bookable=False,  # we can read the price, we cannot sell the seat
        cabin=query.cabin,
        deep_link=build_url(query),
        raw={"aria_label": label, "stops": stops},
    )


class GoogleFlightsScraper(ScraperProvider):
    name = "google_flights"
    supports_flights = True

    async def search_flights(self, query: FlightSearch, http) -> list[FlightQuote]:
        page_html = await self._render(build_url(query), RESULTS_SELECTOR)
        return parse_flights(page_html, query)

    async def probe(self, http) -> str:
        return "browser path enabled (not exercised — probing costs a full page load)"
