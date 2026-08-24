"""Text parsing shared by scrapers: money, clock times, currency words."""

from __future__ import annotations

import re
from datetime import time as dtime

CURRENCY_WORDS = {
    "euros": "EUR",
    "euro": "EUR",
    "us dollars": "USD",
    "dollars": "USD",
    "pounds": "GBP",
    "pound sterling": "GBP",
    "swiss francs": "CHF",
    "japanese yen": "JPY",
    "yen": "JPY",
}

CURRENCY_SYMBOLS = {"€": "EUR", "$": "USD", "£": "GBP", "¥": "JPY", "CHF": "CHF"}

_CLOCK = re.compile(r"(\d{1,2}):(\d{2})\s*([AP]M)?", re.IGNORECASE)
_AMOUNT_IN_TEXT = re.compile(r"([€$£¥]|CHF)\s?([\d][\d.,  ]*)")


def parse_amount(raw: str) -> float | None:
    """Parse a localised number.

    Locales disagree: '1,234' is 1234 in en-GB and 1.234 in de-DE. The rule
    that resolves almost every real case: when both separators appear the
    rightmost one is the decimal point; when only one appears it is a
    thousands separator if exactly three digits follow it.
    """
    cleaned = raw.strip().replace(" ", "").replace(" ", "").replace(" ", "")
    if not cleaned:
        return None

    has_comma, has_dot = "," in cleaned, "." in cleaned

    if has_comma and has_dot:
        decimal_sep = "," if cleaned.rfind(",") > cleaned.rfind(".") else "."
        thousands_sep = "." if decimal_sep == "," else ","
        cleaned = cleaned.replace(thousands_sep, "").replace(decimal_sep, ".")
    elif has_comma or has_dot:
        sep = "," if has_comma else "."
        head, _, tail = cleaned.rpartition(sep)
        if len(tail) == 3 and head.count(sep) == 0 and head.isdigit():
            cleaned = head + tail          # thousands separator
        else:
            cleaned = f"{head}.{tail}"     # decimal separator

    try:
        return float(cleaned)
    except ValueError:
        return None


def parse_money_text(text: str) -> tuple[float, str] | None:
    """Pull the first '€1.234' / '$1,234' style amount out of arbitrary text."""
    m = _AMOUNT_IN_TEXT.search(text)
    if not m:
        return None
    amount = parse_amount(m.group(2))
    if amount is None:
        return None
    return amount, CURRENCY_SYMBOLS.get(m.group(1), "EUR")


def parse_clock(raw: str) -> dtime | None:
    """'7:15 AM' or '19:15' -> time."""
    m = _CLOCK.search(raw.strip())
    if not m:
        return None
    hour, minute, meridiem = int(m.group(1)), int(m.group(2)), m.group(3)
    if meridiem:
        meridiem = meridiem.upper()
        if meridiem == "PM" and hour != 12:
            hour += 12
        elif meridiem == "AM" and hour == 12:
            hour = 0
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        return None
    return dtime(hour, minute)
