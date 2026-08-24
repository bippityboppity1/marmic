"""Booking.com search results via a headless browser.

Booking's markup churns like everyone's, but its `data-testid` attributes are
written for their own test suite and survive redesigns far better than class
names, so we key off those. Extraction runs through a tiny stdlib HTML parser
rather than regex-over-nested-HTML, and is a pure function so it can be
tested against a saved page.
"""

from __future__ import annotations

import html as html_lib
from html.parser import HTMLParser
from urllib.parse import urlencode

from ..models import Freshness, HotelQuote, Money
from ..query import HotelSearch
from ._shared import parse_amount, parse_money_text
from .base import ScraperProvider

CARD_TESTID = "property-card"
RESULTS_SELECTOR = '[data-testid="property-card"]'
WANTED = {
    "title",
    "price-and-discounted-price",
    "review-score",
    "distance",
    "address",
    "recommended-units",
}


def build_url(query: HotelSearch) -> str:
    params = {
        "ss": query.location,
        "checkin": query.check_in.isoformat(),
        "checkout": query.check_out.isoformat(),
        "group_adults": query.adults,
        "no_rooms": query.rooms,
        "group_children": 0,
        "selected_currency": query.currency,
        "order": "price",
    }
    return f"https://www.booking.com/searchresults.html?{urlencode(params)}"


class _CardExtractor(HTMLParser):
    """Collect text of interesting elements, grouped per property card."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.cards: list[dict[str, str]] = []
        self._card: dict[str, str] | None = None
        self._card_depth = 0
        self._depth = 0
        self._capture: str | None = None
        self._capture_depth = 0
        self._buffer: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self._depth += 1
        testid = dict(attrs).get("data-testid")
        if testid == CARD_TESTID:
            self._card = {}
            self._card_depth = self._depth
        elif self._card is not None and testid in WANTED and self._capture is None:
            self._capture = testid
            self._capture_depth = self._depth
            self._buffer = []

    def handle_endtag(self, tag: str) -> None:
        if self._capture is not None and self._depth == self._capture_depth:
            text = " ".join(" ".join(self._buffer).split())
            if text and self._card is not None:
                self._card.setdefault(self._capture, text)
            self._capture = None
        if self._card is not None and self._depth == self._card_depth:
            self.cards.append(self._card)
            self._card = None
        self._depth = max(self._depth - 1, 0)

    def handle_data(self, data: str) -> None:
        if self._capture is not None:
            self._buffer.append(data)


def parse_hotels(page_html: str, query: HotelSearch) -> list[HotelQuote]:
    parser = _CardExtractor()
    parser.feed(html_lib.unescape(page_html))

    quotes: list[HotelQuote] = []
    for card in parser.cards:
        name = card.get("title")
        price_text = card.get("price-and-discounted-price")
        if not name or not price_text:
            continue

        parsed = parse_money_text(price_text)
        if parsed is None:
            continue
        amount, currency = parsed

        quote = HotelQuote(
            provider="booking",
            name=name,
            price_total=Money.of(amount, currency or query.currency),
            nights=query.nights,
            freshness=Freshness.LIVE,
            bookable=False,
            rating=_review_score(card.get("review-score")),
            neighborhood=card.get("address"),
            distance_km_center=_distance_km(card.get("distance")),
            check_in=query.check_in,
            check_out=query.check_out,
            room_type=card.get("recommended-units"),
            deep_link=build_url(query),
            raw=card,
        )
        if query.max_price_per_night and quote.price_per_night and float(
            quote.price_per_night.amount
        ) > query.max_price_per_night:
            continue
        quotes.append(quote)
    return quotes


def _review_score(text: str | None) -> float | None:
    """'Scored 8.6 8.6 Fabulous 1,234 reviews' -> 8.6"""
    if not text:
        return None
    for token in text.replace(",", " ").split():
        value = parse_amount(token)
        if value is not None and 0 < value <= 10:
            return value
    return None


def _distance_km(text: str | None) -> float | None:
    """'1.2 km from centre' -> 1.2; also handles metres."""
    if not text:
        return None
    tokens = text.split()
    for i, token in enumerate(tokens):
        unit = tokens[i + 1].lower() if i + 1 < len(tokens) else ""
        value = parse_amount(token)
        if value is None:
            continue
        if unit.startswith("km"):
            return value
        if unit.startswith("m"):
            return value / 1000
    return None


class BookingScraper(ScraperProvider):
    name = "booking"
    supports_hotels = True

    async def search_hotels(self, query: HotelSearch, http) -> list[HotelQuote]:
        page_html = await self._render(build_url(query), RESULTS_SELECTOR)
        return parse_hotels(page_html, query)

    async def probe(self, http) -> str:
        return "browser path enabled (not exercised — probing costs a full page load)"
