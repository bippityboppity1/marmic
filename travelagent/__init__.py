"""travelagent — real travel prices, with their provenance attached."""

from .config import Config
from .models import (
    FlightQuote,
    Freshness,
    HotelQuote,
    Money,
    ProviderError,
    SearchResult,
)
from .query import FlightSearch, HotelSearch
from .search import search_flights, search_hotels

__version__ = "0.1.0"

__all__ = [
    "Config",
    "FlightQuote",
    "FlightSearch",
    "Freshness",
    "HotelQuote",
    "HotelSearch",
    "Money",
    "ProviderError",
    "SearchResult",
    "search_flights",
    "search_hotels",
]
